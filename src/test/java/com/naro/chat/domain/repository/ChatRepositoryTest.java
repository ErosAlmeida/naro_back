package com.naro.chat.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.chat.domain.entity.ChatMessage;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Queries usadas pela presença (contatos) e pela lista
 * de conversas (última mensagem).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ChatRepositoryTest {

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User leonardo;

    private User maria;

    private User joao;

    private User stranger;

    private Conversation leonardoAndMaria;


    @BeforeEach
    void setUp() {
        leonardo = persistUser("leonardo");
        maria = persistUser("maria");
        joao = persistUser("joao");
        stranger = persistUser("stranger");

        leonardoAndMaria = persistConversation(leonardo, maria);
        persistConversation(maria, joao);
    }


    @Test
    void findContactIdsAmongReturnsOnlyUsersSharingAConversation() {

        Set<UUID> contacts = conversationRepository.findContactIdsAmong(
                leonardo.getId(),
                List.of(maria.getId(), joao.getId(), stranger.getId())
        );

        assertThat(contacts).containsExactly(maria.getId());
    }


    @Test
    void findContactKeycloakIdsReturnsEveryContactExceptTheUser() {

        assertThat(conversationRepository.findContactKeycloakIds(maria.getId()))
                .containsExactlyInAnyOrder(leonardo.getKeycloakId(), joao.getKeycloakId());

        assertThat(conversationRepository.findContactKeycloakIds(stranger.getId()))
                .isEmpty();
    }


    @Test
    void findLastMessagesReturnsNewestMessagePerConversation() {

        Conversation empty = persistConversation(leonardo, joao);

        ChatMessage older = persistMessage(leonardoAndMaria, leonardo, "primeira");
        persistMessage(leonardoAndMaria, maria, "segunda");
        makeOlder(older);
        entityManager.clear();

        List<ChatMessage> last = chatMessageRepository.findLastMessages(
                List.of(leonardoAndMaria.getId(), empty.getId())
        );

        assertThat(last)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getContent()).isEqualTo("segunda");
                    assertThat(message.getSender().getUsername()).isEqualTo("maria");
                });
    }


    private void makeOlder(ChatMessage message) {

        entityManager.getEntityManager()
                .createNativeQuery("""
                        UPDATE chat_messages
                        SET created_at = created_at - INTERVAL '1 minute'
                        WHERE id = :id
                        """)
                .setParameter("id", message.getId())
                .executeUpdate();
    }


    private ChatMessage persistMessage(Conversation conversation, User sender, String content) {
        return entityManager.persistAndFlush(new ChatMessage(conversation, sender, content));
    }


    private Conversation persistConversation(User first, User second) {
        return entityManager.persistAndFlush(
                new Conversation(ConversationType.DIRECT, Set.of(first, second))
        );
    }


    private User persistUser(String username) {

        return entityManager.persist(new User(
                UUID.randomUUID().toString(),
                username,
                username + "@naro.dev",
                null,
                null
        ));
    }
}
