package com.naro.chat;

import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.chat.domain.entity.ChatMessage;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.realtime.application.service.WebSocketTicketService;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tiques do chat (✓ enviada, ✓✓ entregue, ✓✓ azul lida) com banco, broker e
 * STOMP reais: quem confirma entrega e leitura, e o que chega a quem enviou.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=false"}
)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class ChatReceiptsIntegrationTest {

    private static final long EVENT_TIMEOUT_SECONDS = 5;

    private static final long SUBSCRIBE_SETTLE_MILLIS = 300;

    private static final long SILENCE_MILLIS = 800;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private FriendshipRepository friendships;
    @Autowired private WebSocketTicketService ticketService;
    @Autowired private MockMvc mvc;

    private WebSocketStompClient stompClient;

    private final List<StompSession> openSessions = new ArrayList<>();

    private User leonardo;

    private User maria;

    private Conversation conversation;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        stompClient.setDefaultHeartbeat(new long[]{0, 0});

        leonardo = user("leonardo");
        maria = user("maria");
        friendships.saveAndFlush(Friendship.between(leonardo, maria));
        conversation = conversationRepository.save(new Conversation(ConversationType.DIRECT, Set.of(leonardo, maria)));
    }

    @AfterEach
    void tearDown() {
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }

    @Test
    void deliveryAndThenReadingTurnTheSendersTicks() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> leonardoNotifications = subscribe(leonardoSession, "/user/queue/notifications");
        StompSession mariaSession = connect(maria);
        settle();

        send(leonardoSession, "chegou?");
        Map<String, Object> sent = next(leonardoChat);
        Instant createdAt = Instant.parse((String) sent.get("createdAt"));
        next(leonardoNotifications); // CHAT_MESSAGE do próprio envio (outras abas reordenam a lista)

        // ✓: salva, ainda sem chegar à Maria.
        mvc.perform(get("/api/v1/chats").with(account(leonardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.conversations[0].receipts.deliveredAt").doesNotExist())
                .andExpect(jsonPath("$._embedded.conversations[0].receipts.readAt").doesNotExist());

        // ✓✓: a aba da Maria recebeu o aviso e confirmou.
        delivered(mariaSession, conversation.getId(), UUID.fromString((String) sent.get("id")));
        Map<String, Object> delivered = next(leonardoNotifications);
        assertThat(delivered)
                .containsEntry("type", "RECEIPTS")
                .containsEntry("conversationId", conversation.getId().toString());
        assertThat(deliveredAt(delivered)).isEqualTo(createdAt);
        assertThat(readAt(delivered)).isNull();

        // A mesma confirmação de outra aba não gera outro recibo.
        delivered(mariaSession, conversation.getId(), UUID.fromString((String) sent.get("id")));
        assertNothingArrives(leonardoNotifications);

        // ✓✓ azul: a Maria abriu a conversa.
        focus(mariaSession, conversation.getId());
        Map<String, Object> read = next(leonardoNotifications);
        assertThat(read).containsEntry("type", "RECEIPTS");
        assertThat(readAt(read)).isAfterOrEqualTo(createdAt);
        assertThat(deliveredAt(read)).isAfterOrEqualTo(readAt(read));

        // Voltar para a conversa sem mensagem nova não muda tique nenhum.
        focus(mariaSession, null);
        focus(mariaSession, conversation.getId());
        assertNothingArrives(leonardoNotifications);

        // A lista traz o marcador mais recente (a leitura acompanha a conversa aberta).
        String listedReadAt = JSON.readTree(mvc.perform(get("/api/v1/chats").with(account(leonardo)))
                        .andReturn().getResponse().getContentAsString())
                .at("/_embedded/conversations/0/receipts/readAt").asString();
        assertThat(Instant.parse(listedReadAt)).isAfterOrEqualTo(readAt(read));
        // Do lado da Maria, as mensagens dela (nenhuma) seguem sem tique do Leonardo.
        mvc.perform(get("/api/v1/chats").with(account(maria)))
                .andExpect(jsonPath("$._embedded.conversations[0].receipts.readAt").doesNotExist());
    }

    @Test
    void openingTheAppConfirmsEverythingPendingButReadsNothing() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> leonardoNotifications = subscribe(leonardoSession, "/user/queue/notifications");
        settle();

        send(leonardoSession, "1");
        next(leonardoChat);
        send(leonardoSession, "2");
        Instant latest = Instant.parse((String) next(leonardoChat).get("createdAt"));
        next(leonardoNotifications);
        next(leonardoNotifications);

        StompSession mariaSession = connect(maria);
        settle();
        mariaSession.send("/app/chat/delivered", Map.of());

        Map<String, Object> receipt = next(leonardoNotifications);
        assertThat(receipt).containsEntry("type", "RECEIPTS");
        assertThat(deliveredAt(receipt)).isEqualTo(latest);
        assertThat(readAt(receipt)).isNull();
        assertNothingArrives(leonardoNotifications);

        // Entregue não é lido: as duas continuam não lidas para a Maria.
        mvc.perform(get("/api/v1/chats/unread").with(account(maria)))
                .andExpect(jsonPath("$.messages").value(2));
    }

    @Test
    void theServiceWorkerConfirmsDeliveryOverRest() throws Exception {
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> leonardoNotifications = subscribe(leonardoSession, "/user/queue/notifications");
        settle();

        send(leonardoSession, "push");
        Map<String, Object> sent = next(leonardoChat);
        next(leonardoNotifications);

        mvc.perform(post("/api/v1/chats/{id}/delivered", conversation.getId())
                        .with(account(maria))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"" + sent.get("id") + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(deliveredAt(next(leonardoNotifications))).isEqualTo(Instant.parse((String) sent.get("createdAt")));

        // Sem corpo também vale (a conversa toda), e repetir não gera recibo.
        mvc.perform(post("/api/v1/chats/{id}/delivered", conversation.getId()).with(account(maria)))
                .andExpect(status().isNoContent());
        assertNothingArrives(leonardoNotifications);
    }

    @Test
    void onlyAnotherParticipantCanConfirmAndOnlyMessagesFromOthers() throws Exception {
        User eve = user("eve");
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        BlockingQueue<Map<String, Object>> leonardoNotifications = subscribe(leonardoSession, "/user/queue/notifications");
        BlockingQueue<Map<String, Object>> leonardoErrors = subscribe(leonardoSession, "/user/queue/errors");
        StompSession eveSession = connect(eve);
        BlockingQueue<Map<String, Object>> eveErrors = subscribe(eveSession, "/user/queue/errors");
        settle();

        send(leonardoSession, "privada");
        UUID messageId = UUID.fromString((String) next(leonardoChat).get("id"));
        next(leonardoNotifications);

        // Quem não participa: sem erro que revele a conversa, e nada muda.
        delivered(eveSession, conversation.getId(), messageId);
        mvc.perform(post("/api/v1/chats/{id}/delivered", conversation.getId()).with(account(eve)))
                .andExpect(status().isNoContent());
        // O próprio remetente não confirma a própria mensagem.
        delivered(leonardoSession, conversation.getId(), messageId);
        // Id de mensagem de outra conversa (ou inventado) é ignorado.
        delivered(connect(maria), conversation.getId(), UUID.randomUUID());

        assertNothingArrives(leonardoNotifications);
        assertNothingArrives(leonardoErrors);
        assertNothingArrives(eveErrors);
        mvc.perform(get("/api/v1/chats").with(account(leonardo)))
                .andExpect(jsonPath("$._embedded.conversations[0].receipts.deliveredAt").doesNotExist());
    }

    @Test
    void inAGroupTheTicksWaitForEveryone() throws Exception {
        User joao = user("joao");
        Conversation group = conversationRepository.save(new Conversation(ConversationType.GROUP, Set.of(leonardo, maria, joao)));
        ChatMessage message = messageRepository.saveAndFlush(new ChatMessage(group, leonardo, "grupo"));

        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoNotifications = subscribe(leonardoSession, "/user/queue/notifications");
        StompSession mariaSession = connect(maria);
        StompSession joaoSession = connect(joao);
        settle();

        delivered(mariaSession, group.getId(), message.getId());
        Map<String, Object> onlyMaria = next(leonardoNotifications);
        assertThat(onlyMaria).containsEntry("conversationId", group.getId().toString());
        assertThat(deliveredAt(onlyMaria)).as("o João ainda não recebeu").isNull();

        delivered(joaoSession, group.getId(), message.getId());
        assertThat(deliveredAt(next(leonardoNotifications))).isEqualTo(message.getCreatedAt());
    }

    private User user(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(new User("kc-" + name + "-" + suffix, name + suffix, null, name, null));
    }

    private void send(StompSession session, String content) {
        session.send("/app/chat/send", Map.of(
                "conversationId", conversation.getId().toString(),
                "content", content,
                "clientMessageId", UUID.randomUUID().toString()
        ));
    }

    private static void delivered(StompSession session, UUID conversationId, UUID messageId) {
        session.send("/app/chat/delivered", Map.of(
                "conversationId", conversationId.toString(),
                "messageId", messageId.toString()
        ));
    }

    private static void focus(StompSession session, UUID conversationId) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("conversationId", conversationId == null ? null : conversationId.toString());
        session.send("/app/chat/focus", payload);
    }

    private static Instant deliveredAt(Map<String, Object> event) {
        return receipt(event, "deliveredAt");
    }

    private static Instant readAt(Map<String, Object> event) {
        return receipt(event, "readAt");
    }

    private static Instant receipt(Map<String, Object> event, String field) {
        JsonNode value = JSON.valueToTree(event).path("receipts").path(field);
        return value.isNull() || value.isMissingNode() ? null : Instant.parse(value.asString());
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

    private static RequestPostProcessor account(User user) {
        return jwt().jwt(token -> token.subject(user.getKeycloakId()).claim("preferred_username", user.getUsername()));
    }
}
