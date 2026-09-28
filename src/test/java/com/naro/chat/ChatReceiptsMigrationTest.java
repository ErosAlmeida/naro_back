package com.naro.chat;

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

/**
 * V15 (last_delivered_at) sobre um banco como o de produção: as leituras já
 * registradas continuam iguais e passam a contar como entregues.
 */
@Testcontainers(disabledWithoutDocker = true)
class ChatReceiptsMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    private final UUID first = UUID.randomUUID();

    private final UUID second = UUID.randomUUID();

    private final UUID conversation = UUID.randomUUID();

    @Test
    void existingReadsBecomeDeliveredAndDeliveryCanComeBeforeReading() throws SQLException {
        migrate("14");

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
                    INSERT INTO conversation_reads (conversation_id, user_id, last_read_at)
                    VALUES ('%s', '%s', TIMESTAMPTZ '2026-09-27 12:00:00+00')
                    """.formatted(conversation, first));
        }

        migrate(null);

        try (Connection connection = connect(); Statement sql = connection.createStatement()) {
            assertThat(count(sql, """
                    SELECT COUNT(*) FROM conversation_reads
                    WHERE user_id = '%s'
                      AND last_read_at = TIMESTAMPTZ '2026-09-27 12:00:00+00'
                      AND last_delivered_at = last_read_at
                    """.formatted(first))).isEqualTo(1);

            // Entregue sem leitura: o aparelho recebeu, a conversa ainda não foi aberta.
            sql.executeUpdate("""
                    INSERT INTO conversation_reads (conversation_id, user_id, last_delivered_at)
                    VALUES ('%s', '%s', now())
                    """.formatted(conversation, second));
            assertThat(count(sql, "SELECT COUNT(*) FROM conversation_reads WHERE user_id = '%s' AND last_read_at IS NULL"
                    .formatted(second))).isEqualTo(1);
        }
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
