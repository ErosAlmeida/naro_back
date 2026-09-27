package com.naro.encryption;

import com.naro.PostgresTestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12 sobre um banco como o de produção: já com usuários,
 * conversa e mensagens em plaintext antes do E2EE.
 */
@Testcontainers(disabledWithoutDocker = true)
class EncryptionMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    @Test
    void keepsLegacyMessagesAndAllowsOnlyOneMessageShape() throws SQLException {
        migrate("11");

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID legacyMessage = UUID.randomUUID();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES
                    ('%s', 'kc-first', 'first', now(), now()),
                    ('%s', 'kc-second', 'second', now(), now())
                    """.formatted(first, second));
            sql.executeUpdate("INSERT INTO conversations (id, type, created_at, updated_at) VALUES ('%s', 'DIRECT', now(), now())"
                    .formatted(conversation));
            sql.executeUpdate("INSERT INTO conversation_participants (conversation_id, user_id) VALUES ('%s', '%s'), ('%s', '%s')"
                    .formatted(conversation, first, conversation, second));
            sql.executeUpdate("""
                    INSERT INTO chat_messages (id, conversation_id, sender_id, content, created_at)
                    VALUES ('%s', '%s', '%s', 'mensagem antiga', now())
                    """.formatted(legacyMessage, conversation, first));
        }

        migrate(null);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // A mensagem antiga continua intacta e marcada como legada.
            try (var rows = sql.executeQuery("""
                    SELECT content, encrypted, ciphertext, encryption_iv, encryption_version
                    FROM chat_messages WHERE id = '%s'
                    """.formatted(legacyMessage))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("content")).isEqualTo("mensagem antiga");
                assertThat(rows.getBoolean("encrypted")).isFalse();
                assertThat(rows.getString("ciphertext")).isNull();
                assertThat(rows.getString("encryption_iv")).isNull();
                assertThat(rows.getObject("encryption_version")).isNull();
            }

            // Mensagem nova E2EE: só ciphertext.
            sql.executeUpdate(message(conversation, first, "NULL", true, "'Y2lwaGVy'", "'aXY='", "1"));

            // Nunca plaintext e ciphertext juntos, nem E2EE incompleto, nem vazia.
            assertShapeRejected(sql, message(conversation, first, "'texto'", true, "'Y2lwaGVy'", "'aXY='", "1"));
            assertShapeRejected(sql, message(conversation, first, "NULL", true, "'Y2lwaGVy'", "NULL", "1"));
            assertShapeRejected(sql, message(conversation, first, "NULL", true, "'Y2lwaGVy'", "'aXY='", "NULL"));
            assertShapeRejected(sql, message(conversation, first, "'texto'", false, "'Y2lwaGVy'", "NULL", "NULL"));
            assertShapeRejected(sql, message(conversation, first, "NULL", false, "NULL", "NULL", "NULL"));

            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM chat_messages")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void passkeyNeedsAnIdentityAndTheIdentityIsUniquePerUser() throws SQLException {
        migrate(null);

        UUID user = UUID.randomUUID();

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc-e2ee', 'e2ee', now(), now())"
                    .formatted(user));

            assertThatThrownBy(() -> sql.executeUpdate(key(user, "credential-1")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_user_encryption_keys_identity");

            sql.executeUpdate(identity(user));
            assertThatThrownBy(() -> sql.executeUpdate(identity(user)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("pk_user_encryption_identities");

            sql.executeUpdate(key(user, "credential-1"));
            sql.executeUpdate(key(user, "credential-2"));
            assertThatThrownBy(() -> sql.executeUpdate(key(user, "credential-1")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_user_encryption_keys_credential");
        }
    }

    private void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration");

        if (target != null) {
            configuration.target(target);
        }

        configuration.load().migrate();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static void assertShapeRejected(Statement sql, String insert) {
        assertThatThrownBy(() -> sql.executeUpdate(insert))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_chat_messages_payload");
    }

    private static String message(UUID conversation, UUID sender, String content, boolean encrypted,
                                  String ciphertext, String iv, String version) {
        return """
                INSERT INTO chat_messages (id, conversation_id, sender_id, content, encrypted, ciphertext,
                                           encryption_iv, encryption_version, created_at)
                VALUES (gen_random_uuid(), '%s', '%s', %s, %s, %s, %s, %s, now())
                """.formatted(conversation, sender, content, encrypted, ciphertext, iv, version);
    }

    private static String identity(UUID user) {
        return """
                INSERT INTO user_encryption_identities (user_id, public_key, wrapped_private_key, private_key_iv,
                                                        master_key_proof_hash, algorithm, version, created_at)
                VALUES ('%s', 'public', 'wrapped', 'iv', 'hash', 'ECDH-P256', 1, now())
                """.formatted(user);
    }

    private static String key(UUID user, String credentialId) {
        return """
                INSERT INTO user_encryption_keys (id, user_id, credential_id, wrapped_master_key, iv, algorithm,
                                                  version, created_at, updated_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'wrapped', 'iv', 'AES-GCM', 1, now(), now())
                """.formatted(user, credentialId);
    }
}
