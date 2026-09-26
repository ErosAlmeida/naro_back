package com.naro.realtime;

import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.application.service.ChatService;
import com.naro.friend.application.service.FriendService;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.presence.application.service.PresenceStore;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fluxo real: WebSocket + STOMP + ticket + broker + banco.
 *
 * Nenhum mock: o que é verificado é o que um cliente
 * conectado realmente recebe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({
        PostgresTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class
})
@Testcontainers(disabledWithoutDocker = true)
class RealtimeIntegrationTest {

    private static final long EVENT_TIMEOUT_SECONDS = 5;

    /*
     * O SUBSCRIBE é processado de forma assíncrona pelo servidor.
     */
    private static final long SUBSCRIBE_SETTLE_MILLIS = 300;

    private static final long SILENCE_MILLIS = 700;


    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired private FriendshipRepository friendships;
    @Autowired private FriendService friends;
    @Autowired private ChatService chat;
    @Autowired private MockMvc mvc;

    @Autowired
    private WebSocketTicketService ticketService;

    @Autowired
    private PresenceStore presenceStore;

    private WebSocketStompClient stompClient;

    private final List<StompSession> openSessions = new ArrayList<>();

    private User leonardo;

    private User maria;

    private User outsider;

    private Conversation conversation;


    @BeforeEach
    void setUp() {

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
        stompClient.setDefaultHeartbeat(new long[]{0, 0});

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leonardo = userRepository.save(new User("kc-leo-" + suffix, "leonardo" + suffix, null, null, null));
        maria = userRepository.save(new User("kc-maria-" + suffix, "maria" + suffix, null, null, null));
        outsider = userRepository.save(new User("kc-out-" + suffix, "outsider" + suffix, null, null, null));

        friendships.saveAndFlush(Friendship.between(leonardo, maria));
        conversation = conversationRepository.save(
                new Conversation(ConversationType.DIRECT, Set.of(leonardo, maria))
        );
    }


    @AfterEach
    void tearDown() {
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }


    @Test
    void userStaysOnlineUntilLastConnectionClosesAndThenLastSeenIsStored() throws Exception {

        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaPresence = subscribe(mariaSession, "/user/queue/presence");

        StompSession outsiderSession = connect(outsider);
        BlockingQueue<Map<String, Object>> outsiderPresence = subscribe(outsiderSession, "/user/queue/presence");
        settle();

        // 1ª conexão (Chrome): Maria vê Leonardo online.
        StompSession chrome = connect(leonardo);
        Map<String, Object> online = next(mariaPresence);
        assertThat(online)
                .containsEntry("userId", leonardo.getId().toString())
                .containsEntry("status", "ONLINE");

        // 2ª conexão (celular): nenhum evento novo.
        StompSession phone = connect(leonardo);
        assertNothingArrives(mariaPresence);

        // Fecha o Chrome: continua online, sem evento.
        chrome.disconnect();
        assertNothingArrives(mariaPresence);
        assertThat(presenceStore.isOnline(leonardo.getId())).isTrue();

        // Fecha a última conexão: OFFLINE + visto por último.
        Instant beforeLastDisconnect = Instant.now();
        phone.disconnect();

        Map<String, Object> offline = next(mariaPresence);
        assertThat(offline)
                .containsEntry("userId", leonardo.getId().toString())
                .containsEntry("status", "OFFLINE");
        assertThat(Instant.parse((String) offline.get("lastSeenAt"))).isAfterOrEqualTo(beforeLastDisconnect);

        assertThat(userRepository.findById(leonardo.getId()).orElseThrow().getLastSeenAt())
                .isAfterOrEqualTo(beforeLastDisconnect);

        // Quem não compartilha conversa não recebe a presença de Leonardo.
        assertNothingArrives(outsiderPresence);
    }


    @Test
    void typingReachesOnlyTheOtherParticipantWithIdentityFromConnection() throws Exception {

        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaTyping = subscribe(mariaSession, "/user/queue/typing");

        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoTyping = subscribe(leonardoSession, "/user/queue/typing");
        settle();

        // Payload tenta se passar pela Maria: userId/username são ignorados.
        leonardoSession.send("/app/chat/typing", Map.of(
                "conversationId", conversation.getId().toString(),
                "typing", true,
                "userId", maria.getId().toString(),
                "username", "maria-forjada"
        ));

        Map<String, Object> typing = next(mariaTyping);
        assertThat(typing)
                .containsEntry("conversationId", conversation.getId().toString())
                .containsEntry("userId", leonardo.getId().toString())
                .containsEntry("username", leonardo.getUsername())
                .containsEntry("typing", true);

        // Quem digita não recebe o próprio evento.
        assertNothingArrives(leonardoTyping);
    }


    @Test
    void nonParticipantCannotSignalTypingInSomeoneElsesConversation() throws Exception {

        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaTyping = subscribe(mariaSession, "/user/queue/typing");

        StompSession outsiderSession = connect(outsider);
        BlockingQueue<Map<String, Object>> outsiderErrors = subscribe(outsiderSession, "/user/queue/errors");
        settle();

        outsiderSession.send("/app/chat/typing", Map.of(
                "conversationId", conversation.getId().toString(),
                "typing", true
        ));

        assertThat(next(outsiderErrors)).containsEntry("code", "CHAT_ERROR");
        assertNothingArrives(mariaTyping);
    }


    @Test
    void messageIsDeliveredInRealTimeWithClientMessageId() throws Exception {

        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaChat = subscribe(mariaSession, "/user/queue/chat");

        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        settle();

        UUID clientMessageId = UUID.randomUUID();

        leonardoSession.send("/app/chat/send", Map.of(
                "conversationId", conversation.getId().toString(),
                "content", "Oi, Maria!",
                "clientMessageId", clientMessageId.toString()
        ));

        Map<String, Object> received = next(mariaChat);
        assertThat(received)
                .containsEntry("senderId", leonardo.getId().toString())
                .containsEntry("content", "Oi, Maria!")
                .containsEntry("clientMessageId", clientMessageId.toString());

        // O remetente recebe o eco, que confirma o envio da mensagem otimista.
        assertThat(next(leonardoChat)).containsEntry("clientMessageId", clientMessageId.toString());
    }


    @Test
    void friendshipIsRequiredBeforeCreationAndPendingRequestDoesNotGrantAccess() throws Exception {
        var auth = jwt().jwt(token -> token.subject(leonardo.getKeycloakId())
                .claim("preferred_username", leonardo.getUsername()));
        mvc.perform(post("/api/v1/chats/direct/{id}", outsider.getId()).with(auth))
                .andExpect(status().isForbidden());
        var request = friends.sendRequest(outsider.getId(), leonardo);
        mvc.perform(post("/api/v1/chats/direct/{id}", outsider.getId()).with(auth))
                .andExpect(status().isForbidden());
        friends.acceptRequest(request.id(), outsider);
        var created = chat.createDirectConversation(outsider.getId(), leonardo);
        mvc.perform(post("/api/v1/chats/direct/{id}", outsider.getId()).with(auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(created.id().toString()))
                .andExpect(jsonPath("$.canMessage").value(true));
        friends.removeFriend(outsider.getId(), leonardo);
        // Reusing an existing conversation cannot bypass the friendship check.
        mvc.perform(post("/api/v1/chats/direct/{id}", outsider.getId()).with(auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/chats").with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.conversations[?(@.id == '" + created.id() + "')].canMessage")
                        .value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    void endingFriendshipBlocksMessagesAndTypingOnAlreadyConnectedSocketsAndKeepsHistory() throws Exception {
        StompSession sender = connect(leonardo);
        StompSession recipient = connect(maria);
        var sent = subscribe(sender, "/user/queue/chat");
        var received = subscribe(recipient, "/user/queue/chat");
        var recipientTyping = subscribe(recipient, "/user/queue/typing");
        var errors = subscribe(sender, "/user/queue/errors");
        settle();
        sender.send("/app/chat/send", Map.of("conversationId", conversation.getId().toString(),
                "content", "Antes de encerrar a amizade", "clientMessageId", UUID.randomUUID().toString()));
        next(sent);
        next(received);
        friends.removeFriend(maria.getId(), leonardo);
        assertThat(chat.getConversations(leonardo)).filteredOn(c -> c.id().equals(conversation.getId()))
                .extracting(c -> c.canMessage()).containsExactly(false);
        UUID rejectedClientId = UUID.randomUUID();
        sender.send("/app/chat/send", Map.of("conversationId", conversation.getId().toString(),
                "content", "Mensagem que deve ser bloqueada", "clientMessageId", rejectedClientId.toString()));
        assertThat(next(errors)).containsEntry("code", "FRIENDSHIP_REQUIRED")
                .containsEntry("conversationId", conversation.getId().toString())
                .containsEntry("clientMessageId", rejectedClientId.toString());
        sender.send("/app/chat/typing", Map.of("conversationId", conversation.getId().toString(), "typing", true));
        assertThat(next(errors)).containsEntry("code", "FRIENDSHIP_REQUIRED")
                .containsEntry("conversationId", conversation.getId().toString());
        assertNothingArrives(received);
        assertNothingArrives(sent);
        assertNothingArrives(recipientTyping);
        assertThat(chat.getMessages(conversation.getId(), leonardo, PageRequest.of(0, 30)).getContent())
                .extracting(message -> message.content()).containsExactly("Antes de encerrar a amizade");
        assertThat(chat.getMessages(conversation.getId(), maria, PageRequest.of(0, 30)).getTotalElements()).isEqualTo(1);
        assertThatThrownBy(() -> chat.getMessages(conversation.getId(), outsider, PageRequest.of(0, 30)))
                .isInstanceOf(com.naro.exception.ForbiddenException.class);
        var renewed = friends.sendRequest(maria.getId(), leonardo);
        friends.acceptRequest(renewed.id(), maria);
        assertThat(chat.createDirectConversation(maria.getId(), leonardo).id()).isEqualTo(conversation.getId());
        sender.send("/app/chat/send", Map.of("conversationId", conversation.getId().toString(),
                "content", "Amizade retomada", "clientMessageId", UUID.randomUUID().toString()));
        assertThat(next(received)).containsEntry("content", "Amizade retomada");
        next(sent);
        assertThat(chat.getMessages(conversation.getId(), maria, PageRequest.of(0, 30)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void concurrentCreationInBothDirectionsProducesOnlyOneDirectConversation() throws Exception {
        var request = friends.sendRequest(outsider.getId(), leonardo);
        friends.acceptRequest(request.id(), outsider);
        var ready = new CountDownLatch(6);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        List<Future<UUID>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                boolean forward = index % 2 == 0;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race not released");
                    return forward ? chat.createDirectConversation(outsider.getId(), leonardo).id()
                            : chat.createDirectConversation(leonardo.getId(), outsider).id();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ids = new ArrayList<UUID>();
            for (var result : results) ids.add(result.get(20, TimeUnit.SECONDS));
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
            assertThat(conversationRepository.findDirectConversation(ConversationType.DIRECT,
                    leonardo.getId(), outsider.getId())).get().extracting(Conversation::getId).isEqualTo(ids.getFirst());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ticketCannotBeReused() throws Exception {

        String ticket = ticketService.issue(leonardo).ticket();

        openSessions.add(connectWithTicket(ticket));

        assertThatThrownBy(() -> connectWithTicket(ticket))
                .isInstanceOf(ExecutionException.class);
    }


    private StompSession connect(User user) throws Exception {

        StompSession session = connectWithTicket(ticketService.issue(user).ticket());

        openSessions.add(session);

        return session;
    }


    private StompSession connectWithTicket(String ticket) throws Exception {

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("ticket", ticket);

        return stompClient
                .connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        }
                )
                .get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }


    private static BlockingQueue<Map<String, Object>> subscribe(
            StompSession session,
            String destination
    ) {

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


    private static Map<String, Object> next(BlockingQueue<Map<String, Object>> queue)
            throws InterruptedException {

        Map<String, Object> event = queue.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(event).as("evento esperado não chegou").isNotNull();

        return event;
    }


    private static void assertNothingArrives(BlockingQueue<Map<String, Object>> queue)
            throws InterruptedException {

        assertThat(queue.poll(SILENCE_MILLIS, TimeUnit.MILLISECONDS)).isNull();
    }


    private static void settle() throws InterruptedException {
        Thread.sleep(SUBSCRIBE_SETTLE_MILLIS);
    }
}
