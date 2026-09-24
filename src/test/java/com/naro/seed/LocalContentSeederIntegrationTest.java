package com.naro.seed;

import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.seed.SeedManifest.SeedComment;
import com.naro.storage.config.S3StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O perfil local sobe o contexto e o ApplicationReadyEvent já roda o seed,
 * contra o PostgreSQL e o MinIO dos containers (ambos em localhost).
 */
@SpringBootTest
@ActiveProfiles("local")
@Import({
        PostgresTestcontainersConfiguration.class,
        MinioTestcontainersConfiguration.class
})
@Testcontainers(disabledWithoutDocker = true)
class LocalContentSeederIntegrationTest {

    @Autowired
    private LocalContentSeeder seeder;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3StorageProperties storage;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void startupSeedsTheWholeManifestOnceWithEveryImageInStorage() throws IOException {
        SeedManifest manifest = manifest();
        int channels = manifest.channels().size();
        int publications = manifest.channels().stream().mapToInt(channel -> channel.publications().size()).sum();
        int comments = manifest.channels().stream()
                .flatMap(channel -> channel.publications().stream())
                .mapToInt(publication -> count(publication.commentsOrEmpty()))
                .sum();

        assertThat(count("SELECT count(*) FROM users WHERE keycloak_id LIKE 'seed:%'")).isEqualTo(channels);
        assertThat(count("SELECT count(*) FROM channels")).isEqualTo(channels);
        assertThat(count("SELECT count(*) FROM publications")).isEqualTo(publications);
        assertThat(count("SELECT count(*) FROM comments")).isEqualTo(comments);
        assertThat(count("SELECT count(*) FROM comments WHERE parent_comment_id IS NOT NULL")).isPositive();
        assertThat(count("SELECT count(*) FROM likes WHERE publication_id IS NOT NULL")).isGreaterThanOrEqualTo(2L * publications);
        assertThat(count("SELECT count(*) FROM channel_follows")).isGreaterThanOrEqualTo(3L * channels);

        // A linha do tempo é coerente: nada no futuro, nada antes do que responde.
        assertThat(count("SELECT count(*) FROM publications WHERE created_at > now()")).isZero();
        assertThat(count("""
                SELECT count(*) FROM comments c JOIN publications p ON p.id = c.publication_id
                WHERE c.created_at < p.created_at
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM likes l JOIN publications p ON p.id = l.publication_id
                WHERE l.channel_id = p.channel_id
                """)).isZero();

        List<String> imageKeys = jdbc.sql("""
                        SELECT image_key FROM publications
                        UNION ALL SELECT profile_image_key FROM channels
                        UNION ALL SELECT banner_image_key FROM channels
                        """)
                .query(String.class)
                .list();
        assertThat(imageKeys).hasSize(publications + 2 * channels).doesNotContainNull();
        imageKeys.forEach(key -> assertThat(s3Client.headObject(head -> head.bucket(storage.bucket()).key(key)).contentType())
                .isEqualTo("image/jpeg"));

        assertThat(seeder.seed()).isFalse();
        assertThat(count("SELECT count(*) FROM publications")).isEqualTo(publications);
    }

    private SeedManifest manifest() throws IOException {
        try (InputStream input = new ClassPathResource("seed/content.json").getInputStream()) {
            return jsonMapper.readValue(input, SeedManifest.class);
        }
    }

    private static int count(List<SeedComment> comments) {
        return comments.stream().mapToInt(comment -> 1 + count(comment.repliesOrEmpty())).sum();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
