package com.naro.notification;

import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.notification.domain.repository.PushSubscriptionRepository;
import com.naro.notification.webpush.PushDeliveryResult;
import com.naro.notification.webpush.WebPushClient;
import com.naro.notification.webpush.WebPushTestSupport;
import com.naro.realtime.application.service.WebSocketTicketService;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Do envio pelo WebSocket até o push, com banco, broker e STOMP reais.
 * Só o push service é de mentira (WebPushClient), para contar entregas
 * e simular falhas. Perfil de teste = chat legado (texto), com prévia.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=false"}
)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class ChatNotificationIntegrationTest {

    private static final long EVENT_TIMEOUT_SECONDS = 5;

    private static final long SUBSCRIBE_SETTLE_MILLIS = 300;

    private static final long SILENCE_MILLIS = 800;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @MockitoBean private WebPushClient webPushClient;

    @Autowired private UserRepository userRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private FriendshipRepository friendships;
    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private WebSocketTicketService ticketService;
    @Autowired private MockMvc mvc;
    @Autowired private TransactionTemplate transactions;

    private WebSocketStompClient stompClient;

    private final List<StompSession> openSessions = new ArrayList<>();

    private User leonardo;

    private User maria;

    private Conversation conversation;

    private String mariaPhone;

    private String leonardoLaptop;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        stompClient.setDefaultHeartbeat(new long[]{0, 0});

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leonardo = userRepository.save(new User("kc-leo-" + suffix, "leonardo" + suffix, null, "Leonardo", null));
        maria = userRepository.save(new User("kc-maria-" + suffix, "maria" + suffix, null, "Maria", "Souza"));
        friendships.saveAndFlush(Friendship.between(leonardo, maria));
        conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT, Set.of(leonardo, maria)));

        mariaPhone = subscribe(maria);
        leonardoLaptop = subscribe(leonardo);

        when(webPushClient.isEnabled()).thenReturn(true);
        when(webPushClient.send(any(), any(), any(), any(), any())).thenReturn(PushDeliveryResult.DELIVERED);
    }

    @AfterEach
    void tearDown() {
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }

    @Test
    void aMessageToSomeoneWithTheAppClosedBecomesAPush() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        settle();

        send(leonardoSession, "Você vai entrar hoje?");

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(webPushClient, timeout(5_000)).send(eq(mariaPhone), any(), any(), payload.capture(), any());
        verify(webPushClient, after(SILENCE_MILLIS).never()).send(eq(leonardoLaptop), any(), any(), any(), any());

        JsonNode json = JSON.readTree(payload.getValue());
        assertThat(json.get("type").asString()).isEqualTo("CHAT_MESSAGE");
        assertThat(json.get("conversationId").asString()).isEqualTo(conversation.getId().toString());
        assertThat(json.get("sender").get("displayName").asString()).isEqualTo("Leonardo");
        assertThat(json.get("preview").asString()).isEqualTo("Você vai entrar hoje?");
        assertThat(json.get("url").asString()).isEqualTo("/chat/" + conversation.getId());
        assertThat(json.get("unreadConversations").asLong()).isEqualTo(1);
        assertThat(json.toString()).doesNotContain(maria.getKeycloakId()).doesNotContain(leonardo.getKeycloakId());
    }

    @Test
    void noPushWhileTheConversationIsOnScreenAndPushAgainAfterLeavingIt() throws Exception {
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaChat = subscribe(mariaSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> mariaNotifications = subscribe(mariaSession, "/user/queue/notifications");
        StompSession leonardoSession = connect(leonardo);
        settle();

        focus(mariaSession, conversation.getId());
        assertThat(next(mariaNotifications))
                .containsEntry("type", "CONVERSATION_READ")
                .containsEntry("conversationId", conversation.getId().toString());

        send(leonardoSession, "estou na conversa");
        assertThat(next(mariaChat)).containsEntry("content", "estou na conversa");
        verify(webPushClient, after(SILENCE_MILLIS).never()).send(eq(mariaPhone), any(), any(), any(), any());

        focus(mariaSession, null);
        settle();

        send(leonardoSession, "e agora?");
        verify(webPushClient, timeout(5_000)).send(eq(mariaPhone), any(), any(), any(), any());
    }

    @Test
    void closingTheTabEndsTheFocus() throws Exception {
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaNotifications = subscribe(mariaSession, "/user/queue/notifications");
        StompSession leonardoSession = connect(leonardo);
        settle();
        focus(mariaSession, conversation.getId());
        next(mariaNotifications);

        mariaSession.disconnect();
        settle();

        send(leonardoSession, "fechou?");
        verify(webPushClient, timeout(5_000)).send(eq(mariaPhone), any(), any(), any(), any());
    }

    @Test
    void theInAppNoticeReachesEveryScreenWithoutTheMessageText() throws Exception {
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> notifications = subscribe(mariaSession, "/user/queue/notifications");
        StompSession leonardoSession = connect(leonardo);
        settle();

        send(leonardoSession, "texto privado");

        Map<String, Object> notice = next(notifications);
        assertThat(notice)
                .containsEntry("type", "CHAT_MESSAGE")
                .containsEntry("conversationId", conversation.getId().toString())
                .containsEntry("senderId", leonardo.getId().toString())
                .containsEntry("senderUsername", leonardo.getUsername())
                .doesNotContainKeys("content", "ciphertext", "iv");
        assertThat(notice.toString()).doesNotContain("texto privado");
    }

    @Test
    void unreadCountsFollowNewMessagesAndReading() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        settle();

        send(leonardoSession, "1");
        send(leonardoSession, "2");
        verify(webPushClient, timeout(5_000).times(2)).send(eq(mariaPhone), any(), any(), any(), any());

        var mariaAccount = account(maria);
        mvc.perform(get("/api/v1/chats").with(mariaAccount))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.conversations[0].unreadCount").value(2));
        mvc.perform(get("/api/v1/chats/unread").with(mariaAccount))
                .andExpect(jsonPath("$.conversations").value(1))
                .andExpect(jsonPath("$.messages").value(2));
        // As próprias mensagens nunca contam.
        mvc.perform(get("/api/v1/chats/unread").with(account(leonardo)))
                .andExpect(jsonPath("$.messages").value(0));

        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaNotifications = subscribe(mariaSession, "/user/queue/notifications");
        settle();
        focus(mariaSession, conversation.getId());
        next(mariaNotifications);

        mvc.perform(get("/api/v1/chats/unread").with(mariaAccount))
                .andExpect(jsonPath("$.conversations").value(0))
                .andExpect(jsonPath("$.messages").value(0));
        mvc.perform(get("/api/v1/chats").with(mariaAccount))
                .andExpect(jsonPath("$._embedded.conversations[0].unreadCount").value(0));
    }

    @Test
    void readingOnOneDeviceClearsTheCounterOnTheOthers() throws Exception {
        StompSession phone = connect(maria);
        StompSession laptop = connect(maria);
        BlockingQueue<Map<String, Object>> laptopNotifications = subscribe(laptop, "/user/queue/notifications");
        settle();

        focus(phone, conversation.getId());

        assertThat(next(laptopNotifications))
                .containsEntry("type", "CONVERSATION_READ")
                .containsEntry("conversationId", conversation.getId().toString());
    }

    @Test
    void focusingSomeoneElsesConversationIsIgnored() throws Exception {
        User eve = userRepository.save(new User("kc-eve-" + UUID.randomUUID(), "eve" + UUID.randomUUID().toString().substring(0, 8), null, null, null));
        StompSession eveSession = connect(eve);
        BlockingQueue<Map<String, Object>> eveNotifications = subscribe(eveSession, "/user/queue/notifications");
        BlockingQueue<Map<String, Object>> eveErrors = subscribe(eveSession, "/user/queue/errors");
        StompSession leonardoSession = connect(leonardo);
        settle();

        focus(eveSession, conversation.getId());
        assertNothingArrives(eveNotifications);
        assertNothingArrives(eveErrors);

        // Nem o foco da Eve silencia o push da Maria.
        send(leonardoSession, "oi Maria");
        verify(webPushClient, timeout(5_000)).send(eq(mariaPhone), any(), any(), any(), any());
    }

    @Test
    void aBrokenPushServiceNeverBlocksTheMessage() throws Exception {
        when(webPushClient.send(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("push service down"));
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaChat = subscribe(mariaSession, "/user/queue/chat");
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> leonardoErrors = subscribe(leonardoSession, "/user/queue/errors");
        settle();

        send(leonardoSession, "chega mesmo assim");

        assertThat(next(mariaChat)).containsEntry("content", "chega mesmo assim");
        assertThat(next(leonardoChat)).containsEntry("content", "chega mesmo assim");
        assertNothingArrives(leonardoErrors);
        assertThat(messageRepository.findByConversation_IdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 5)))
                .hasSize(1);
    }

    @Test
    void anExpiredSubscriptionIsRemovedAutomatically() throws Exception {
        when(webPushClient.send(eq(mariaPhone), any(), any(), any(), any())).thenReturn(PushDeliveryResult.EXPIRED);
        String mariaLaptop = subscribe(maria);
        StompSession leonardoSession = connect(leonardo);
        settle();

        send(leonardoSession, "alguém aí?");

        verify(webPushClient, timeout(5_000)).send(eq(mariaLaptop), any(), any(), any(), any());
        awaitUntil(() -> subscriptions.findByEndpoint(mariaPhone).isEmpty());
        assertThat(subscriptions.findByEndpoint(mariaLaptop)).isPresent();
        assertThat(subscriptions.findByEndpoint(mariaLaptop).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void aRejectedSubscriptionIsDisabledUntilTheBrowserRegistersAgain() throws Exception {
        when(webPushClient.send(eq(mariaPhone), any(), any(), any(), any())).thenReturn(PushDeliveryResult.REJECTED);
        StompSession leonardoSession = connect(leonardo);
        settle();

        send(leonardoSession, "oi");

        awaitUntil(() -> !subscriptions.findByEndpoint(mariaPhone).orElseThrow().isEnabled());
    }

    @Test
    void typingNeverBecomesAPush() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        settle();

        leonardoSession.send("/app/chat/typing", Map.of("conversationId", conversation.getId().toString(), "typing", true));

        verify(webPushClient, after(SILENCE_MILLIS).never()).send(any(), any(), any(), any(), any());
    }

    private String subscribe(User user) {
        String endpoint = "https://fcm.googleapis.com/fcm/send/" + UUID.randomUUID();
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        transactions.executeWithoutResult(_ -> subscriptions.upsert(UUID.randomUUID(), user.getId(), endpoint,
                browser.p256dh(), browser.auth(), null, null, Instant.now()));
        return endpoint;
    }

    private void send(StompSession session, String content) {
        session.send("/app/chat/send", Map.of(
                "conversationId", conversation.getId().toString(),
                "content", content,
                "clientMessageId", UUID.randomUUID().toString()
        ));
    }

    private static void focus(StompSession session, UUID conversationId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conversationId == null ? null : conversationId.toString());
        session.send("/app/chat/focus", payload);
    }

    private StompSession connect(User user) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("ticket", ticketService.issue(user).ticket());

        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        openSessions.add(session);

        return session;
    }

    private static BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();

        session.subscribe(destination, new StompFrameHandler() {

            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });

        return queue;
    }

    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        Map<String, Object> event = queue.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(event).as("evento esperado não chegou").isNotNull();
        return event;
    }

    private static void assertNothingArrives(BlockingQueue<Map<String, Object>> queue) throws InterruptedException {
        assertThat(queue.poll(SILENCE_MILLIS, TimeUnit.MILLISECONDS)).isNull();
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(EVENT_TIMEOUT_SECONDS));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condição esperada não aconteceu").isBefore(deadline);
            Thread.sleep(50);
        }
    }

    private static RequestPostProcessor account(User user) {
        return jwt().jwt(token -> token.subject(user.getKeycloakId()).claim("preferred_username", user.getUsername()));
    }
}
