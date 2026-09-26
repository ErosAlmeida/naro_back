package com.naro.channel;

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

@Testcontainers(disabledWithoutDocker = true)
class ChannelFollowMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    @Test
    void migrationCreatesChannelToChannelFollowsWithDefaultsAndDatabaseConstraints() throws SQLException {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        UUID owner = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID follower = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        UUID following = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc-owner', 'owner', now(), now()), ('%s', 'kc-other', 'other', now(), now())"
                    .formatted(owner, otherOwner));
            sql.executeUpdate("INSERT INTO channels (id, owner_id, name, slug, created_at, updated_at) VALUES ('%s', '%s', 'Follower', 'follower', now(), now()), ('%s', '%s', 'Sibling', 'sibling', now(), now()), ('%s', '%s', 'Following', 'following', now(), now())"
                    .formatted(follower, owner, sibling, owner, following, otherOwner));

            sql.executeUpdate(insert(follower, following));
            sql.executeUpdate(insert(sibling, following));

            try (var rows = sql.executeQuery("SELECT favorite, interaction_count, last_interaction_at, relevance_score, created_at FROM channel_follows WHERE follower_channel_id = '%s'".formatted(follower))) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean("favorite")).isFalse();
                assertThat(rows.getLong("interaction_count")).isZero();
                assertThat(rows.getObject("last_interaction_at")).isNull();
                assertThat(rows.getObject("relevance_score")).isNull();
                assertThat(rows.getObject("created_at")).isNotNull();
            }

            assertThatThrownBy(() -> sql.executeUpdate(insert(follower, following)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_channel_follows_pair");
            assertThatThrownBy(() -> sql.executeUpdate(insert(follower, follower)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_channel_follows_not_self");
            assertThatThrownBy(() -> sql.executeUpdate(insert(follower, UUID.randomUUID())))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_channel_follows_following");
            assertThatThrownBy(() -> sql.executeUpdate(insert(UUID.randomUUID(), following)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_channel_follows_follower");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE channel_follows SET interaction_count = -1 WHERE follower_channel_id = '%s'".formatted(follower)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_channel_follows_interaction_count");
            assertThatThrownBy(() -> sql.executeUpdate("UPDATE channel_follows SET relevance_score = 1.1 WHERE follower_channel_id = '%s'".formatted(follower)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_channel_follows_relevance_score");
        }
    }

    private static String insert(UUID follower, UUID following) {
        return "INSERT INTO channel_follows (id, follower_channel_id, following_channel_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())"
                .formatted(follower, following);
    }
}
