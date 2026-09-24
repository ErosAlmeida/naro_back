package com.naro.seed;

import com.naro.seed.SeedManifest.SeedChannel;
import com.naro.seed.SeedManifest.SeedComment;
import com.naro.seed.SeedManifest.SeedPublication;
import com.naro.storage.config.S3StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Massa de dados para desenvolvimento: usuários, canais de segmentos
 * diferentes, publicações com imagem, comentários, likes e follows.
 *
 * Só existe no perfil {@code local} (o padrão sem perfil ativo) e só escreve
 * quando o banco e o storage estão em localhost. O jar não leva
 * {@code seed/**}, então produção também não teria o que inserir.
 *
 * Roda uma vez: os usuários do seed são reconhecidos pelo keycloak_id
 * {@code seed:<username>}. Para refazer, apague-os; o cascade leva canais,
 * publicações, comentários, likes e follows junto.
 */
@Component
@Profile("local")
@ConditionalOnBooleanProperty("app.seed.enabled")
public class LocalContentSeeder {

    private static final String KEYCLOAK_ID_PREFIX = "seed:";

    private static final Logger log = LoggerFactory.getLogger(LocalContentSeeder.class);

    private static final String MANIFEST = "seed/content.json";
    private static final String IMAGES = "seed/images/";
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );

    /* A mesma massa a cada banco novo: nada de dados diferentes por máquina. */
    private static final long RANDOM_SEED = 20260924L;

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final S3Client s3Client;
    private final S3StorageProperties storage;
    private final JsonMapper jsonMapper;

    public LocalContentSeeder(
            JdbcClient jdbc,
            DataSource dataSource,
            TransactionTemplate transaction,
            S3Client s3Client,
            S3StorageProperties storage,
            JsonMapper jsonMapper
    ) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transaction = transaction;
        this.s3Client = s3Client;
        this.storage = storage;
        this.jsonMapper = jsonMapper;
    }

    /* Depois dos ApplicationRunners: o S3BucketInitializer já criou o bucket. */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            seed();
        } catch (RuntimeException exception) {
            // Seed é conveniência de desenvolvimento: o app sobe mesmo sem ele.
            log.error("Seed local falhou; nada foi gravado no banco.", exception);
        }
    }

    /** @return true quando gravou o seed; false quando não havia o que fazer. */
    boolean seed() {
        String jdbcUrl = jdbcUrl();
        if (!isLocalJdbcUrl(jdbcUrl) || !isLocalHost(storage.endpoint().getHost())) {
            log.warn("Seed local ignorado: banco ({}) ou storage ({}) fora de localhost.", jdbcUrl, storage.endpoint());
            return false;
        }

        if (alreadySeeded()) {
            log.info("Seed local já aplicado; nada a fazer.");
            return false;
        }

        ClassPathResource manifestFile = new ClassPathResource(MANIFEST);
        if (!manifestFile.exists()) {
            log.warn("Seed local ignorado: {} não está no classpath.", MANIFEST);
            return false;
        }

        SeedManifest manifest = readManifest(manifestFile);
        List<String> uploadedKeys = new ArrayList<>();
        try {
            Totals totals = transaction.execute(status -> new Writer(uploadedKeys).write(manifest));
            log.info("Seed local aplicado: {}.", totals);
            return true;
        } catch (RuntimeException exception) {
            uploadedKeys.forEach(this::deleteQuietly);
            throw exception;
        }
    }

    static boolean isLocalJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return false;
        }
        try {
            return isLocalHost(URI.create(jdbcUrl.substring("jdbc:".length())).getHost());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    static boolean isLocalHost(String host) {
        return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
    }

    private String jdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read the datasource URL", exception);
        }
    }

    private boolean alreadySeeded() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE keycloak_id LIKE :prefix)")
                .param("prefix", KEYCLOAK_ID_PREFIX + "%")
                .query(Boolean.class)
                .single();
    }

    private SeedManifest readManifest(ClassPathResource file) {
        try (InputStream input = file.getInputStream()) {
            return jsonMapper.readValue(input, SeedManifest.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void deleteQuietly(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(storage.bucket()).key(key).build());
        } catch (RuntimeException ignored) {
            // Objeto órfão no MinIO local não atrapalha nada.
        }
    }

    private record Totals(int users, int channels, int publications, int comments, int likes, int follows) {
    }

    private record SeededChannel(UUID id, Instant createdAt) {
    }

    private record SeededItem(UUID id, UUID authorId, Instant createdAt) {
    }

    /** Uma execução do seed: datas e sorteios saem do mesmo Random. */
    private final class Writer {

        private final List<String> uploadedKeys;
        private final Random random = new Random(RANDOM_SEED);
        private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        private final Map<String, SeededChannel> channels = new LinkedHashMap<>();
        private final List<SeededItem> publications = new ArrayList<>();
        private final List<SeededItem> comments = new ArrayList<>();
        private int likes;
        private int follows;

        Writer(List<String> uploadedKeys) {
            this.uploadedKeys = uploadedKeys;
        }

        Totals write(SeedManifest manifest) {
            // Canais criados antes do seed (os do próprio dev) também ganham seguidores.
            List<SeededChannel> existing = jdbc.sql("SELECT id, created_at FROM channels")
                    .query((row, index) -> new SeededChannel(
                            row.getObject("id", UUID.class),
                            row.getObject("created_at", OffsetDateTime.class).toInstant()))
                    .list();

            manifest.channels().forEach(this::insertChannel);
            manifest.channels().forEach(this::insertPublications);
            likePublications();
            likeComments();
            followEachOther();
            followExisting(existing);

            return new Totals(channels.size(), channels.size(), publications.size(), comments.size(), likes, follows);
        }

        private void insertChannel(SeedChannel seed) {
            Instant createdAt = now.minus(Duration.ofDays(35 + random.nextInt(40))).minusSeconds(random.nextInt(86_400));
            UUID userId = UUID.randomUUID();
            UUID channelId = UUID.randomUUID();

            jdbc.sql("""
                            INSERT INTO users (id, keycloak_id, username, email, first_name, last_name, bio, created_at, updated_at)
                            VALUES (:id, :keycloakId, :username, :email, :firstName, :lastName, :bio, :createdAt, :createdAt)
                            """)
                    .param("id", userId)
                    .param("keycloakId", KEYCLOAK_ID_PREFIX + seed.user().username())
                    .param("username", seed.user().username())
                    .param("email", seed.user().username() + "@seed.naro.local")
                    .param("firstName", seed.user().firstName())
                    .param("lastName", seed.user().lastName())
                    .param("bio", seed.user().bio())
                    .param("createdAt", timestamp(createdAt.minus(Duration.ofHours(1 + random.nextInt(48)))))
                    .update();

            jdbc.sql("""
                            INSERT INTO channels (id, owner_id, name, slug, description, profile_image_key, banner_image_key, created_at, updated_at)
                            VALUES (:id, :ownerId, :name, :slug, :description, :profileImageKey, :bannerImageKey, :createdAt, :createdAt)
                            """)
                    .param("id", channelId)
                    .param("ownerId", userId)
                    .param("name", seed.name())
                    .param("slug", seed.slug())
                    .param("description", seed.description())
                    .param("profileImageKey", upload("channels", seed.profileImage()))
                    .param("bannerImageKey", upload("channels", seed.bannerImage()))
                    .param("createdAt", timestamp(createdAt))
                    .update();

            channels.put(seed.key(), new SeededChannel(channelId, createdAt));
        }

        private void insertPublications(SeedChannel seed) {
            SeededChannel author = channel(seed.key());
            // Três semanas de conteúdo, intercalado entre os canais no feed.
            Instant oldest = latest(author.createdAt(), now.minus(Duration.ofDays(21)));

            for (SeedPublication publication : seed.publications()) {
                UUID id = UUID.randomUUID();
                Instant createdAt = between(oldest, now.minus(Duration.ofHours(1)));

                jdbc.sql("""
                                INSERT INTO publications (id, channel_id, content, image_key, created_at)
                                VALUES (:id, :channelId, :content, :imageKey, :createdAt)
                                """)
                        .param("id", id)
                        .param("channelId", author.id())
                        .param("content", publication.content())
                        .param("imageKey", upload("publications", publication.image()))
                        .param("createdAt", timestamp(createdAt))
                        .update();

                SeededItem seeded = new SeededItem(id, author.id(), createdAt);
                publications.add(seeded);
                publication.commentsOrEmpty().forEach(comment -> insertComment(seeded, null, comment));
            }
        }

        private void insertComment(SeededItem publication, SeededItem parent, SeedComment seed) {
            UUID id = UUID.randomUUID();
            SeededChannel author = channel(seed.author());
            Instant after = parent == null ? publication.createdAt() : parent.createdAt();
            Instant createdAt = between(after.plus(Duration.ofMinutes(5)), earliest(now, after.plus(Duration.ofDays(2))));

            jdbc.sql("""
                            INSERT INTO comments (id, publication_id, parent_comment_id, channel_id, content, created_at)
                            VALUES (:id, :publicationId, :parentId, :channelId, :content, :createdAt)
                            """)
                    .param("id", id)
                    .param("publicationId", publication.id())
                    .param("parentId", parent == null ? null : parent.id())
                    .param("channelId", author.id())
                    .param("content", seed.content())
                    .param("createdAt", timestamp(createdAt))
                    .update();

            SeededItem seeded = new SeededItem(id, author.id(), createdAt);
            comments.add(seeded);
            seed.repliesOrEmpty().forEach(reply -> insertComment(publication, seeded, reply));
        }

        private void likePublications() {
            for (SeededItem publication : publications) {
                for (SeededChannel channel : othersThan(publication.authorId(), 2 + random.nextInt(channels.size() - 2))) {
                    insertLike(channel.id(), "publication_id", publication);
                }
            }
        }

        private void likeComments() {
            for (SeededItem comment : comments) {
                for (SeededChannel channel : othersThan(comment.authorId(), random.nextInt(4))) {
                    insertLike(channel.id(), "comment_id", comment);
                }
            }
        }

        private void insertLike(UUID channelId, String targetColumn, SeededItem target) {
            // A coluna vem das duas constantes acima, nunca de entrada externa.
            jdbc.sql("""
                            INSERT INTO likes (id, channel_id, %s, created_at)
                            VALUES (:id, :channelId, :targetId, :createdAt)
                            """.formatted(targetColumn))
                    .param("id", UUID.randomUUID())
                    .param("channelId", channelId)
                    .param("targetId", target.id())
                    .param("createdAt", timestamp(between(target.createdAt(), now)))
                    .update();
            likes++;
        }

        private void followEachOther() {
            for (SeededChannel follower : channels.values()) {
                for (SeededChannel following : othersThan(follower.id(), 3 + random.nextInt(6))) {
                    insertFollow(follower, following);
                }
            }
        }

        private void followExisting(List<SeededChannel> existing) {
            for (SeededChannel follower : channels.values()) {
                for (SeededChannel following : existing) {
                    if (random.nextInt(10) < 7) {
                        insertFollow(follower, following);
                    }
                }
            }
        }

        private void insertFollow(SeededChannel follower, SeededChannel following) {
            // interaction_count, last_interaction_at e relevance_score ficam nos defaults do follow.
            jdbc.sql("""
                            INSERT INTO channel_follows (id, follower_channel_id, following_channel_id, favorite, created_at)
                            VALUES (:id, :followerId, :followingId, :favorite, :createdAt)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("followerId", follower.id())
                    .param("followingId", following.id())
                    .param("favorite", random.nextInt(5) == 0)
                    .param("createdAt", timestamp(between(latest(follower.createdAt(), following.createdAt()), now)))
                    .update();
            follows++;
        }

        private List<SeededChannel> othersThan(UUID excluded, int count) {
            List<SeededChannel> candidates = new ArrayList<>(channels.values().stream()
                    .filter(channel -> !channel.id().equals(excluded))
                    .toList());
            Collections.shuffle(candidates, random);
            return candidates.subList(0, Math.min(count, candidates.size()));
        }

        private SeededChannel channel(String key) {
            SeededChannel channel = channels.get(key);
            if (channel == null) {
                throw new IllegalStateException("Unknown seed channel: " + key);
            }
            return channel;
        }

        private String upload(String directory, String image) {
            String extension = image.substring(image.lastIndexOf('.') + 1).toLowerCase();
            String contentType = CONTENT_TYPES.get(extension);
            if (contentType == null) {
                throw new IllegalStateException("Unsupported seed image: " + image);
            }

            byte[] bytes;
            try {
                bytes = new ClassPathResource(IMAGES + image).getContentAsByteArray();
            } catch (IOException exception) {
                throw new UncheckedIOException("Could not read seed image " + image, exception);
            }

            // Mesmo formato de chave do S3ObjectStorageService.
            String key = directory + "/" + UUID.randomUUID() + "." + (extension.equals("jpeg") ? "jpg" : extension);
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(storage.bucket()).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(bytes)
            );
            uploadedKeys.add(key);
            return key;
        }

        private Instant between(Instant from, Instant to) {
            long seconds = Duration.between(from, to).toSeconds();
            return seconds <= 0 ? to : from.plusSeconds(random.nextLong(seconds));
        }

        private static Instant latest(Instant first, Instant second) {
            return first.isAfter(second) ? first : second;
        }

        private static Instant earliest(Instant first, Instant second) {
            return first.isBefore(second) ? first : second;
        }

        private static OffsetDateTime timestamp(Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
    }
}
