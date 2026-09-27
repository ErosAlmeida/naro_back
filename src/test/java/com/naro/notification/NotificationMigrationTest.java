package com.naro.notification;

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
 * V13 (push_subscriptions) e V14 (conversation_reads) sobre um banco como o
 * de produção: conversas e mensagens já existentes, nada reescrito.
 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    private final UUID first = UUID.randomUUID();

    private final UUID second = UUID.randomUUID();

    private final UUID outsider = UUID.randomUUID();

    private final UUID conversation = UUID.randomUUID();

    @Test
    void existingHistoryStartsReadAndOnlyParticipantsHaveReadMarkers() throws SQLException {
        migrate("12");

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES
                    ('%s', 'kc-first', 'first', now(), now()),
                    ('%s', 'kc-second', 'second', now(), now()),
                    ('%s', 'kc-outsider', 'outsider', now(), now())
                    """.formatted(first, second, outsider));
            sql.executeUpdate("INSERT INTO conversations (id, type, created_at, updated_at) VALUES ('%s', 'DIRECT', now(), now())"
                    .formatted(conversation));
            sql.executeUpdate("INSERT INTO conversation_participants (conversation_id, user_id) VALUES ('%s', '%s'), ('%s', '%s')"
                    .formatted(conversation, first, conversation, second));
            sql.executeUpdate("""
                    INSERT INTO chat_messages (id, conversation_id, sender_id, content, created_at)
                    VALUES (gen_random_uuid(), '%s', '%s', 'antiga', now() - interval '1 day')
                    """.formatted(conversation, first));
        }

        migrate(null);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            // Nenhuma mensagem antiga vira "não lida" depois do deploy.
            assertThat(count(sql, "SELECT COUNT(*) FROM conversation_reads")).isEqualTo(2);
            assertThat(count(sql, """
                    SELECT COUNT(*) FROM chat_messages m
                    JOIN conversation_reads r ON r.conversation_id = m.conversation_id AND r.user_id = '%s'
                    WHERE m.sender_id <> '%s' AND m.created_at > r.last_read_at
                    """.formatted(second, second))).isZero();
            assertThat(count(sql, "SELECT COUNT(*) FROM chat_messages")).isEqualTo(1);

            assertThatThrownBy(() -> sql.executeUpdate(
                    "INSERT INTO conversation_reads (conversation_id, user_id, last_read_at) VALUES ('%s', '%s', now())"
                            .formatted(conversation, outsider)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_conversation_reads_participant");
        }
    }

    @Test
    void oneRowPerBrowserEndpointAndDevicesGoAwayWithTheAccount() throws SQLException {
        migrate(null);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc-first', 'first', now(), now())"
                    .formatted(first));
            sql.executeUpdate(subscription(first, "https://fcm.googleapis.com/fcm/send/a"));
            sql.executeUpdate(subscription(first, "https://fcm.googleapis.com/fcm/send/b"));

            assertThatThrownBy(() -> sql.executeUpdate(subscription(first, "https://fcm.googleapis.com/fcm/send/a")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_push_subscriptions_endpoint");

            sql.executeUpdate("DELETE FROM users WHERE id = '%s'".formatted(first));
            assertThat(count(sql, "SELECT COUNT(*) FROM push_subscriptions")).isZero();
        }
    }

    private static String subscription(UUID user, String endpoint) {
        return """
                INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, created_at, updated_at)
                VALUES (gen_random_uuid(), '%s', '%s', 'key', 'auth', now(), now())
                """.formatted(user, endpoint);
    }

    private static long count(Statement sql, String query) throws SQLException {
        try (var rows = sql.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
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
}
