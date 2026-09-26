package com.naro.friend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FriendshipMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void protectsSymmetricPairsAndRequestLifecycleWhileKeepingHistoryAndUsers() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc-first', 'first', now(), now()), ('%s', 'kc-second', 'second', now(), now())"
                    .formatted(first, second));

            sql.executeUpdate(request(first, second, "PENDING", "NULL"));
            assertThatThrownBy(() -> sql.executeUpdate(request(first, second, "PENDING", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_friend_requests_pending_pair");
            assertThatThrownBy(() -> sql.executeUpdate(request(second, first, "PENDING", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_friend_requests_pending_pair");
            assertThatThrownBy(() -> sql.executeUpdate(request(first, first, "PENDING", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_friend_requests_not_self");
            assertThatThrownBy(() -> sql.executeUpdate(request(first, UUID.randomUUID(), "PENDING", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_friend_requests_receiver");
            assertThatThrownBy(() -> sql.executeUpdate(request(UUID.randomUUID(), second, "PENDING", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_friend_requests_sender");
            assertThatThrownBy(() -> sql.executeUpdate(request(first, second, "UNKNOWN", "now()")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_friend_requests_status");
            assertThatThrownBy(() -> sql.executeUpdate(request(first, second, "PENDING", "now()")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_friend_requests_responded_at");
            assertThatThrownBy(() -> sql.executeUpdate(request(first, second, "ACCEPTED", "NULL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_friend_requests_responded_at");

            sql.executeUpdate("UPDATE friend_requests SET status = 'REJECTED', responded_at = now()");
            sql.executeUpdate(request(second, first, "CANCELED", "now()"));
            sql.executeUpdate(request(first, second, "ACCEPTED", "now()"));
            sql.executeUpdate(request(second, first, "PENDING", "NULL"));
            assertCount(sql, "friend_requests", 4);

            sql.executeUpdate(friendship(first, second));
            assertThatThrownBy(() -> sql.executeUpdate(friendship(first, second)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_friendships_pair");
            assertThatThrownBy(() -> sql.executeUpdate(friendship(second, first)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uk_friendships_pair");
            assertThatThrownBy(() -> sql.executeUpdate(friendship(first, first)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_friendships_not_self");
            assertThatThrownBy(() -> sql.executeUpdate(friendship(first, UUID.randomUUID())))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_friendships_user_b");
            assertThatThrownBy(() -> sql.executeUpdate(friendship(UUID.randomUUID(), second)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_friendships_user_a");

            sql.executeUpdate("DELETE FROM friendships");
            assertCount(sql, "friendships", 0);
            assertCount(sql, "users", 2);
            assertCount(sql, "friend_requests", 4);
        }
    }

    @Test
    void removingFriendSeedDeletesOnlyFixtureAccountsAndTheirChildren() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target("10")
                .load().migrate();
        UUID real = UUID.randomUUID();
        UUID mock = UUID.randomUUID();
        UUID publicSeed = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("""
                    INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES
                    ('%s', 'real-keycloak-subject', 'mock.real-account', now(), now()),
                    ('%s', 'friend-seed:ana', 'mock.ana', now(), now()),
                    ('%s', 'seed:public', 'public-content', now(), now())
                    """.formatted(real, mock, publicSeed));
            sql.executeUpdate(friendship(mock, real));
            sql.executeUpdate(request(real, mock, "PENDING", "NULL"));
            sql.executeUpdate("""
                    INSERT INTO channels (id, owner_id, name, slug, created_at, updated_at)
                    VALUES ('%s', '%s', 'Fixture channel', 'fixture-channel', now(), now())
                    """.formatted(channel, mock));
            sql.executeUpdate("""
                    INSERT INTO publications (id, channel_id, content, image_key, created_at)
                    VALUES (gen_random_uuid(), '%s', 'fixture', 'fixture.png', now())
                    """.formatted(channel));
            sql.executeUpdate("INSERT INTO conversations (id, type, created_at, updated_at) VALUES ('%s', 'DIRECT', now(), now())"
                    .formatted(conversation));
            sql.executeUpdate("INSERT INTO conversation_participants (conversation_id, user_id) VALUES ('%s', '%s'), ('%s', '%s')"
                    .formatted(conversation, mock, conversation, real));
            sql.executeUpdate("""
                    INSERT INTO chat_messages (id, conversation_id, sender_id, content, created_at) VALUES
                    (gen_random_uuid(), '%s', '%s', 'fixture message', now()),
                    (gen_random_uuid(), '%s', '%s', 'real message', now())
                    """.formatted(conversation, mock, conversation, real));
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            assertCount(sql, "users", 2);
            assertCount(sql, "friendships", 0);
            assertCount(sql, "friend_requests", 0);
            assertCount(sql, "channels", 0);
            assertCount(sql, "publications", 0);
            assertCount(sql, "chat_messages", 1);
            assertCount(sql, "conversation_participants", 1);
            assertCount(sql, "conversations", 1);
            try (var rows = sql.executeQuery("SELECT keycloak_id FROM users ORDER BY keycloak_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("real-keycloak-subject");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("seed:public");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private static String request(UUID sender, UUID receiver, String status, String respondedAt) {
        return "INSERT INTO friend_requests (id, sender_id, receiver_id, status, created_at, responded_at) VALUES (gen_random_uuid(), '%s', '%s', '%s', now(), %s)"
                .formatted(sender, receiver, status, respondedAt);
    }

    private static String friendship(UUID first, UUID second) {
        return "INSERT INTO friendships (id, user_a_id, user_b_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())"
                .formatted(first, second);
    }

    private static void assertCount(Statement sql, String table, int expected) throws SQLException {
        try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(expected);
        }
    }
}
