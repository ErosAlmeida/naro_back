package com.naro.user.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User leonardo;

    private User marina;


    @BeforeEach
    void setUp() {
        leonardo = persistUser("leonardo", "Leonardo", "Vilela");
        marina = persistUser("marinacosta", "Marina", "Costa");
        persistUser("mar_io", "Mário", "Souza");
    }


    @Test
    void updateLastSeenAtWritesOnlyLastSeenAndNotUpdatedAt() {

        // PostgreSQL stores microseconds; compare against its persisted value, not
        // the JVM's potentially nanosecond timestamp retained after persistAndFlush.
        entityManager.refresh(marina);
        Instant updatedAtBefore = marina.getUpdatedAt();
        Instant lastSeen = Instant.now().truncatedTo(ChronoUnit.MICROS);

        int updated = userRepository.updateLastSeenAt(marina.getId(), lastSeen);
        entityManager.clear();

        User reloaded = userRepository.findById(marina.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(reloaded.getLastSeenAt()).isEqualTo(lastSeen);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
    }


    @Test
    void findLastSeenByIdsReturnsNullForUsersNeverSeen() {

        List<UserLastSeen> lastSeen = userRepository.findLastSeenByIds(List.of(leonardo.getId()));

        assertThat(lastSeen).containsExactly(new UserLastSeen(leonardo.getId(), null));
    }


    @Test
    void searchMatchesUsernameOrFullNameCaseInsensitiveAndExcludesCaller() {

        assertThat(userRepository.search("%costa%", leonardo.getId(), PageRequest.of(0, 10)))
                .extracting(User::getUsername)
                .containsExactly("marinacosta");

        assertThat(userRepository.search("%leonardo%", leonardo.getId(), PageRequest.of(0, 10)))
                .isEmpty();
    }


    @Test
    void escapedUnderscoreMatchesLiteralUnderscoreOnly() {

        assertThat(userRepository.search("%mar\\_%", leonardo.getId(), PageRequest.of(0, 10)))
                .extracting(User::getUsername)
                .containsExactly("mar_io");
    }


    private User persistUser(String username, String firstName, String lastName) {

        return entityManager.persistAndFlush(new User(
                UUID.randomUUID().toString(),
                username,
                username + "@naro.dev",
                firstName,
                lastName
        ));
    }
}
