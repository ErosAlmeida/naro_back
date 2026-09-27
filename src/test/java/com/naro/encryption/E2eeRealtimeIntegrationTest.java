package com.naro.encryption;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebSocket + STOMP + broker + banco com naro.security.e2ee.required=true.
 * Mesma configuração do EncryptionEndpointsIntegrationTest (contexto reaproveitado).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed.enabled=false", "naro.security.e2ee.required=true"}
)
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class E2eeRealtimeIntegrationTest {

    private static final long EVENT_TIMEOUT_SECONDS = 5;

    private static final long SUBSCRIBE_SETTLE_MILLIS = 300;

    private static final long SILENCE_MILLIS = 700;

    private static final SecureRandom RANDOM = new SecureRandom();

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

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        leonardo = userRepository.save(new User("kc-leo-" + suffix, "leonardo" + suffix, null, null, null));
        maria = userRepository.save(new User("kc-maria-" + suffix, "maria" + suffix, null, null, null));
        friendships.saveAndFlush(Friendship.between(leonardo, maria));
        conversation = conversationRepository.save(
                new Conversation(ConversationType.DIRECT, Set.of(leonardo, maria)));
    }

    @AfterEach
    void tearDown() {
        openSessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
    }

    @Test
    void ciphertextTravelsUnchangedAndIsStoredWithoutPlaintext() throws Exception {
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaChat = subscribe(mariaSession, "/user/queue/chat");
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> leonardoChat = subscribe(leonardoSession, "/user/queue/chat");
        settle();

        String ciphertext = bytes(48);
        String iv = bytes(12);
        UUID clientMessageId = UUID.randomUUID();

        leonardoSession.send("/app/chat/send", Map.of(
                "conversationId", conversation.getId().toString(),
                "ciphertext", ciphertext,
                "iv", iv,
                "encryptionVersion", 1,
                "clientMessageId", clientMessageId.toString()
        ));

        Map<String, Object> received = next(mariaChat);
        assertThat(received)
                .containsEntry("senderId", leonardo.getId().toString())
                .containsEntry("encrypted", true)
                .containsEntry("ciphertext", ciphertext)
                .containsEntry("iv", iv)
                .containsEntry("encryptionVersion", 1)
                .containsEntry("content", null);
        assertThat(next(leonardoChat)).containsEntry("clientMessageId", clientMessageId.toString());

        ChatMessage stored = messageRepository
                .findByConversation_IdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 10))
                .getContent()
                .getFirst();
        assertThat(stored.isEncrypted()).isTrue();
        assertThat(stored.getContent()).isNull();
        assertThat(stored.getCiphertext()).isEqualTo(ciphertext);
        assertThat(stored.getEncryptionIv()).isEqualTo(iv);
    }

    @Test
    void plaintextIsRejectedAndNeverDeliveredOrStored() throws Exception {
        StompSession mariaSession = connect(maria);
        BlockingQueue<Map<String, Object>> mariaChat = subscribe(mariaSession, "/user/queue/chat");
        StompSession leonardoSession = connect(leonardo);
        BlockingQueue<Map<String, Object>> errors = subscribe(leonardoSession, "/user/queue/errors");
        settle();

        UUID clientMessageId = UUID.randomUUID();

        leonardoSession.send("/app/chat/send", Map.of(
                "conversationId", conversation.getId().toString(),
                "content", "Olá",
                "clientMessageId", clientMessageId.toString()
        ));

        Map<String, Object> error = next(errors);
        assertThat(error)
                .containsEntry("code", "E2EE_REQUIRED")
                .containsEntry("conversationId", conversation.getId().toString())
                .containsEntry("clientMessageId", clientMessageId.toString());
        assertThat((String) error.get("message")).doesNotContain("Olá");

        assertNothingArrives(mariaChat);
        assertThat(messageRepository.findByConversation_IdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void legacyMessagesStayReadableNextToEncryptedOnes() throws Exception {
        messageRepository.saveAndFlush(new ChatMessage(conversation, leonardo, "mensagem antiga"));
        messageRepository.saveAndFlush(ChatMessage.encrypted(conversation, maria, bytes(40), bytes(12), 1));

        var auth = jwt().jwt(token -> token.subject(leonardo.getKeycloakId())
                .claim("preferred_username", leonardo.getUsername()));

        mvc.perform(get("/api/v1/chats/{id}/messages", conversation.getId()).with(auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.messages.length()").value(2))
                .andExpect(jsonPath("$._embedded.messages[?(@.encrypted == false)].content")
                        .value(org.hamcrest.Matchers.contains("mensagem antiga")))
                .andExpect(jsonPath("$._embedded.messages[?(@.encrypted == true)].content")
                        .value(org.hamcrest.Matchers.contains((Object) null)));
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

    private static String bytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
}
