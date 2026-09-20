package com.naro.publication.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.publication.domain.entity.Publication;
import com.naro.user.domain.entity.User;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PublicationRepositoryTest {

    @Autowired
    private PublicationRepository publicationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User leonardo;

    private User maria;


    @BeforeEach
    void setUp() {
        leonardo = persistUser("leonardo");
        maria = persistUser("maria");
    }


    @Test
    void findWithAuthorByIdLoadsAuthorEagerly() {

        Publication publication = persistPublication(leonardo, "Oi");
        entityManager.clear();

        Publication found = publicationRepository.findWithAuthorById(publication.getId()).orElseThrow();

        assertThat(Hibernate.isInitialized(found.getAuthor())).isTrue();
        assertThat(found.getAuthor().getUsername()).isEqualTo("leonardo");
    }


    @Test
    void findAllReturnsNewestFirstWithAuthorLoaded() {

        Publication oldest = persistPublication(leonardo, "primeira");
        persistPublication(maria, "segunda");
        makeOlder(oldest);
        entityManager.clear();

        Page<Publication> page =
                publicationRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(Publication::getContent)
                .containsExactly("segunda", "primeira");
        assertThat(page.getContent())
                .allSatisfy(publication ->
                        assertThat(Hibernate.isInitialized(publication.getAuthor())).isTrue()
                );
    }


    @Test
    void findByAuthorReturnsOnlyThatAuthorsPublications() {

        persistPublication(leonardo, "do leonardo 1");
        persistPublication(leonardo, "do leonardo 2");
        persistPublication(maria, "da maria");
        entityManager.clear();

        Page<Publication> page = publicationRepository.findByAuthor_IdOrderByCreatedAtDescIdDesc(
                leonardo.getId(),
                PageRequest.of(0, 10)
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .allSatisfy(publication ->
                        assertThat(publication.getAuthor().getId()).isEqualTo(leonardo.getId())
                );
    }


    private void makeOlder(Publication publication) {

        entityManager.getEntityManager()
                .createNativeQuery("""
                        UPDATE publications
                        SET created_at = created_at - INTERVAL '1 minute'
                        WHERE id = :id
                        """)
                .setParameter("id", publication.getId())
                .executeUpdate();
    }


    private Publication persistPublication(User author, String content) {

        return entityManager.persistAndFlush(
                new Publication(author, content, "publications/" + UUID.randomUUID() + ".png")
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
