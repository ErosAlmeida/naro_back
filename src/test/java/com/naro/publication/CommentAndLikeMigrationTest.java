package com.naro.publication;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Valida as migrations consolidadas em um banco vazio, incluindo
 * a estrutura final e as regras de integridade. Cada teste usa um banco novo.
 */
@Testcontainers(disabledWithoutDocker = true)
class CommentAndLikeMigrationTest {

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));


    @Test
    void createsFinalTablesFromAnEmptyDatabase() throws SQLException {

        migrateTo("latest");

        UUID user = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID comment = UUID.randomUUID();

        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc', 'leo', now(), now())".formatted(user));
            sql.executeUpdate("INSERT INTO publications (id, author_id, content, image_key, created_at) VALUES ('%s', '%s', 'post', 'k.png', now())".formatted(publication, user));
            sql.executeUpdate("INSERT INTO comments (id, publication_id, author_id, content, created_at) VALUES ('%s', '%s', '%s', 'comentário', now())".formatted(comment, publication, user));
            sql.executeUpdate("INSERT INTO likes (id, publication_id, user_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())".formatted(publication, user));
        }

        migrateTo("latest");

        try (Connection connection = connection(); Statement sql = connection.createStatement()) {

            ResultSet comments = sql.executeQuery("SELECT id, publication_id, parent_comment_id, content FROM comments");
            assertThat(comments.next()).isTrue();
            assertThat(comments.getObject("id")).isEqualTo(comment);
            assertThat(comments.getObject("publication_id")).isEqualTo(publication);
            assertThat(comments.getObject("parent_comment_id")).isNull();
            assertThat(comments.getString("content")).isEqualTo("comentário");
            assertThat(comments.next()).isFalse();

            ResultSet likes = sql.executeQuery("SELECT user_id, publication_id, comment_id FROM likes");
            assertThat(likes.next()).isTrue();
            assertThat(likes.getObject("user_id")).isEqualTo(user);
            assertThat(likes.getObject("publication_id")).isEqualTo(publication);
            assertThat(likes.getObject("comment_id")).isNull();
            assertThat(likes.next()).isFalse();

            ResultSet oldTables = sql.executeQuery("SELECT to_regclass('publication_comments'), to_regclass('publication_likes')");
            oldTables.next();
            assertThat(oldTables.getObject(1)).isNull();
            assertThat(oldTables.getObject(2)).isNull();

            ResultSet users = sql.executeQuery("SELECT last_seen_at FROM users");
            assertThat(users.next()).isTrue();
            assertThat(users.getObject("last_seen_at")).isNull();
        }
    }


    @Test
    void enforcesLikeTargetAndUniquenessRules() throws SQLException {

        migrateTo("latest");

        UUID leonardo = UUID.randomUUID();
        UUID maria = UUID.randomUUID();
        UUID publication = UUID.randomUUID();
        UUID comment = UUID.randomUUID();

        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (id, keycloak_id, username, created_at, updated_at) VALUES ('%s', 'kc-leo', 'leo', now(), now()), ('%s', 'kc-maria', 'maria', now(), now())".formatted(leonardo, maria));
            sql.executeUpdate("INSERT INTO publications (id, author_id, content, image_key, created_at) VALUES ('%s', '%s', 'post', 'k.png', now())".formatted(publication, leonardo));
            sql.executeUpdate("INSERT INTO comments (id, publication_id, parent_comment_id, author_id, content, created_at) VALUES ('%s', '%s', NULL, '%s', 'oi', now())".formatted(comment, publication, leonardo));
            sql.executeUpdate("INSERT INTO likes (id, user_id, publication_id, comment_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', NULL, now())".formatted(maria, publication));
            sql.executeUpdate("INSERT INTO likes (id, user_id, publication_id, comment_id, created_at) VALUES (gen_random_uuid(), '%s', NULL, '%s', now())".formatted(maria, comment));
        }

        migrateTo("latest");

        try (Connection connection = connection(); Statement sql = connection.createStatement()) {

            ResultSet likes = sql.executeQuery("SELECT count(*) FILTER (WHERE publication_id IS NOT NULL), count(*) FILTER (WHERE comment_id IS NOT NULL) FROM likes");
            likes.next();
            assertThat(likes.getLong(1)).isEqualTo(1);
            assertThat(likes.getLong(2)).isEqualTo(1);

            // Nenhum alvo / os dois alvos.
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO likes (id, user_id, created_at) VALUES (gen_random_uuid(), '%s', now())".formatted(leonardo)))
                    .hasMessageContaining("ck_likes_publication_xor_comment");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO likes (id, user_id, publication_id, comment_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', '%s', now())".formatted(leonardo, publication, comment)))
                    .hasMessageContaining("ck_likes_publication_xor_comment");

            // Duplicados.
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO likes (id, user_id, publication_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())".formatted(maria, publication)))
                    .hasMessageContaining("uk_likes_publication_user");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO likes (id, user_id, comment_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())".formatted(maria, comment)))
                    .hasMessageContaining("uk_likes_comment_user");

            // Outro usuário pode curtir os mesmos.
            sql.executeUpdate("INSERT INTO likes (id, user_id, publication_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())".formatted(leonardo, publication));
            sql.executeUpdate("INSERT INTO likes (id, user_id, comment_id, created_at) VALUES (gen_random_uuid(), '%s', '%s', now())".formatted(leonardo, comment));
        }
    }


    private void migrateTo(String target) {

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }


    private Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
