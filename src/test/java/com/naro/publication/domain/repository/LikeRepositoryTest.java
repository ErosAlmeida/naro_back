package com.naro.publication.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Like;
import com.naro.publication.domain.entity.Publication;
import com.naro.channel.domain.entity.Channel;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * Publication
 *  └── comment
 *       └── reply
 *            └── nestedReply
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class LikeRepositoryTest {

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Channel leonardo;

    private Channel maria;

    private Publication publication;

    private Comment comment;

    private Comment nestedReply;


    @BeforeEach
    void setUp() {
        leonardo = persistUser("leonardo");
        maria = persistUser("maria");
        publication = entityManager.persistAndFlush(new Publication(leonardo, "content", "publications/x.png"));
        comment = entityManager.persistAndFlush(Comment.onPublication(maria, publication, "comentário"));
        Comment reply = entityManager.persistAndFlush(Comment.reply(leonardo, comment, "resposta"));
        nestedReply = entityManager.persistAndFlush(Comment.reply(maria, reply, "resposta da resposta"));
    }


    @Test
    void likeOnPublication() {

        likeRepository.saveAndFlush(Like.publication(maria, publication));

        assertThat(likeRepository.existsByChannelIdAndPublicationId(maria.getId(), publication.getId())).isTrue();
        assertThat(likeRepository.existsByChannelIdAndPublicationId(leonardo.getId(), publication.getId())).isFalse();
        assertThat(likeRepository.countByPublicationId(publication.getId())).isEqualTo(1);

        // Um like na publicação não é um like em comentário.
        assertThat(likeRepository.countByCommentId(comment.getId())).isZero();
    }


    @Test
    void likeOnComment() {

        likeRepository.saveAndFlush(Like.comment(leonardo, comment));

        assertThat(likeRepository.existsByChannelIdAndCommentId(leonardo.getId(), comment.getId())).isTrue();
        assertThat(likeRepository.countByCommentId(comment.getId())).isEqualTo(1);
        assertThat(likeRepository.countByPublicationId(publication.getId())).isZero();
    }


    @Test
    void likeOnNestedReply() {

        likeRepository.saveAndFlush(Like.comment(leonardo, nestedReply));
        entityManager.clear();

        Like reloaded = likeRepository.findAll().getFirst();

        assertThat(reloaded.getComment().getId()).isEqualTo(nestedReply.getId());
        assertThat(reloaded.getPublication()).isNull();
        assertThat(likeRepository.countByCommentId(comment.getId())).isZero();
    }


    @Test
    void sameUserCanLikeAPublicationAndItsComments() {

        likeRepository.saveAndFlush(Like.publication(leonardo, publication));
        likeRepository.saveAndFlush(Like.comment(leonardo, comment));
        likeRepository.saveAndFlush(Like.comment(leonardo, nestedReply));

        assertThat(likeRepository.count()).isEqualTo(3);
    }


    @Test
    void databaseRejectsDuplicateLikeOnPublication() {

        likeRepository.saveAndFlush(Like.publication(leonardo, publication));

        assertThatThrownBy(() -> likeRepository.saveAndFlush(Like.publication(leonardo, publication)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_likes_publication_channel");
    }


    @Test
    void databaseRejectsDuplicateLikeOnComment() {

        likeRepository.saveAndFlush(Like.comment(leonardo, nestedReply));

        assertThatThrownBy(() -> likeRepository.saveAndFlush(Like.comment(leonardo, nestedReply)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_likes_comment_channel");
    }


    /*
     * As factories do Like não permitem esses estados; o banco
     * também não, mesmo com SQL direto.
     */
    @Test
    void databaseRejectsLikeWithoutPublicationAndComment() {

        assertThatThrownBy(() -> rawInsert(leonardo.getId(), null, null))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_likes_publication_xor_comment");
    }


    @Test
    void databaseRejectsLikeOnPublicationAndCommentAtTheSameTime() {

        assertThatThrownBy(() -> rawInsert(leonardo.getId(), publication.getId(), comment.getId()))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_likes_publication_xor_comment");
    }


    @Test
    void unlikeRemovesOnlyTheGivenUsersLike() {

        likeRepository.save(Like.publication(leonardo, publication));
        likeRepository.save(Like.publication(maria, publication));
        likeRepository.save(Like.comment(leonardo, comment));
        likeRepository.flush();

        likeRepository.deleteByChannelIdAndPublicationId(leonardo.getId(), publication.getId());
        likeRepository.flush();

        assertThat(likeRepository.existsByChannelIdAndPublicationId(leonardo.getId(), publication.getId())).isFalse();
        assertThat(likeRepository.existsByChannelIdAndPublicationId(maria.getId(), publication.getId())).isTrue();
        assertThat(likeRepository.existsByChannelIdAndCommentId(leonardo.getId(), comment.getId())).isTrue();

        likeRepository.deleteByChannelIdAndCommentId(leonardo.getId(), comment.getId());
        likeRepository.flush();

        assertThat(likeRepository.countByCommentId(comment.getId())).isZero();
    }


    @Test
    void batchCountAndLikedIdsForAPageOfComments() {

        likeRepository.save(Like.comment(leonardo, comment));
        likeRepository.save(Like.comment(maria, comment));
        likeRepository.save(Like.comment(maria, nestedReply));
        likeRepository.flush();

        List<UUID> ids = List.of(comment.getId(), nestedReply.getId());

        assertThat(likeRepository.countPerComment(ids))
                .containsExactlyInAnyOrder(new IdCount(comment.getId(), 2), new IdCount(nestedReply.getId(), 1));
        assertThat(likeRepository.findCommentIdsLikedBy(leonardo.getId(), ids))
                .containsExactly(comment.getId());
    }


    @Test
    void batchCountAndLikedIdsForAPageOfPublications() {

        Publication quiet = entityManager.persistAndFlush(new Publication(leonardo, "sem likes", "publications/y.png"));
        likeRepository.save(Like.publication(maria, publication));
        likeRepository.save(Like.comment(maria, comment));
        likeRepository.flush();

        List<UUID> ids = List.of(publication.getId(), quiet.getId());

        assertThat(likeRepository.countPerPublication(ids))
                .containsExactly(new IdCount(publication.getId(), 1));
        assertThat(likeRepository.findPublicationIdsLikedBy(maria.getId(), ids))
                .containsExactly(publication.getId());
    }


    @Test
    void listsWhoLikedNewestFirst() {

        Like older = likeRepository.saveAndFlush(Like.comment(maria, nestedReply));
        likeRepository.saveAndFlush(Like.comment(leonardo, nestedReply));
        makeOlder(older);
        entityManager.clear();

        assertThat(likeRepository.findByCommentIdOrderByCreatedAtDesc(nestedReply.getId(), PageRequest.of(0, 10)).getContent())
                .extracting(like -> like.getChannel().getSlug())
                .containsExactly("leonardo", "maria");
    }


    @Test
    void deletingACommentRemovesItsLikesAndTheLikesOfItsReplies() {

        likeRepository.save(Like.comment(leonardo, comment));
        likeRepository.save(Like.comment(leonardo, nestedReply));
        likeRepository.save(Like.publication(leonardo, publication));
        likeRepository.flush();
        entityManager.clear();

        commentRepository.delete(commentRepository.findById(comment.getId()).orElseThrow());
        entityManager.flush();

        assertThat(likeRepository.count()).isEqualTo(1);
        assertThat(likeRepository.countByPublicationId(publication.getId())).isEqualTo(1);
    }


    private void rawInsert(UUID userId, UUID publicationId, UUID commentId) {

        entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO likes (id, channel_id, publication_id, comment_id, created_at)
                        VALUES (gen_random_uuid(), :userId, CAST(:publicationId AS uuid), CAST(:commentId AS uuid), now())
                        """)
                .setParameter("userId", userId)
                .setParameter("publicationId", publicationId == null ? null : publicationId.toString())
                .setParameter("commentId", commentId == null ? null : commentId.toString())
                .executeUpdate();
    }


    private void makeOlder(Like like) {

        entityManager.getEntityManager()
                .createNativeQuery("UPDATE likes SET created_at = created_at - INTERVAL '1 minute' WHERE id = :id")
                .setParameter("id", like.getId())
                .executeUpdate();
    }


    private Channel persistUser(String username) {
        com.naro.user.domain.entity.User owner = entityManager.persist(new com.naro.user.domain.entity.User(UUID.randomUUID().toString(), username, null, null, null));
        return entityManager.persist(com.naro.support.Fixtures.channel(owner, username));
    }
}
