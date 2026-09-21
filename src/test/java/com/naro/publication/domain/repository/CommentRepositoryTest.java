package com.naro.publication.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Publication;
import com.naro.channel.domain.entity.Channel;

import jakarta.persistence.PersistenceException;

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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class CommentRepositoryTest {

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Channel author;

    private Publication publication;


    @BeforeEach
    void setUp() {
        author = persistUser("author");
        publication = persistPublication();
    }


    /*
     * Publication
     *  └── A
     *       └── B
     *            └── C
     *                 └── D
     */
    @Test
    void repliesCanNestWithoutDepthLimitAndAlwaysKeepTheOriginalPublication() {

        Comment a = persist(Comment.onPublication(author, publication, "A"));
        Comment b = persist(Comment.reply(author, a, "B"));
        Comment c = persist(Comment.reply(author, b, "C"));
        Comment d = persist(Comment.reply(author, c, "D"));
        entityManager.clear();

        Comment reloaded = commentRepository.findById(d.getId()).orElseThrow();

        assertThat(reloaded.getParentComment().getId()).isEqualTo(c.getId());
        assertThat(reloaded.getPublication().getId()).isEqualTo(publication.getId());
        assertThat(reloaded.isReply()).isTrue();

        // Buscar a resposta não carrega a cadeia de pais nem a publicação.
        assertThat(Hibernate.isInitialized(reloaded.getParentComment())).isFalse();
        assertThat(Hibernate.isInitialized(reloaded.getPublication())).isFalse();
    }


    @Test
    void publicationPageContainsOnlyTopLevelCommentsNewestFirst() {

        Comment older = persist(Comment.onPublication(author, publication, "mais antigo"));
        persist(Comment.onPublication(author, publication, "mais novo"));
        persist(Comment.reply(author, older, "resposta"));
        makeOlder(older);
        entityManager.clear();

        Page<Comment> page = commentRepository.findByPublication_IdAndParentCommentIsNullOrderByCreatedAtDescIdDesc(
                publication.getId(),
                PageRequest.of(0, 10)
        );

        assertThat(page.getContent())
                .extracting(Comment::getContent)
                .containsExactly("mais novo", "mais antigo");
        assertThat(page.getContent())
                .allSatisfy(comment -> assertThat(Hibernate.isInitialized(comment.getAuthor())).isTrue());
    }


    @Test
    void repliesPageContainsOnlyDirectRepliesInConversationOrder() {

        Comment root = persist(Comment.onPublication(author, publication, "raiz"));
        Comment first = persist(Comment.reply(author, root, "primeira"));
        persist(Comment.reply(author, root, "segunda"));
        persist(Comment.reply(author, first, "neta"));
        makeOlder(first);
        entityManager.clear();

        Page<Comment> replies = commentRepository.findByParentComment_IdOrderByCreatedAtAscIdAsc(
                root.getId(),
                PageRequest.of(0, 10)
        );

        assertThat(replies.getContent())
                .extracting(Comment::getContent)
                .containsExactly("primeira", "segunda");
    }


    @Test
    void countsDirectRepliesAndAllCommentsOfAPublication() {

        Comment root = persist(Comment.onPublication(author, publication, "raiz"));
        Comment reply = persist(Comment.reply(author, root, "r1"));
        persist(Comment.reply(author, root, "r2"));
        persist(Comment.reply(author, reply, "r1.1"));
        Comment withoutReplies = persist(Comment.onPublication(author, publication, "sozinho"));

        assertThat(commentRepository.countRepliesPerComment(List.of(root.getId(), reply.getId(), withoutReplies.getId())))
                .containsExactlyInAnyOrder(new IdCount(root.getId(), 2), new IdCount(reply.getId(), 1));

        assertThat(commentRepository.countPerPublication(List.of(publication.getId())))
                .containsExactly(new IdCount(publication.getId(), 5));
    }


    @Test
    void findByIdAndPublicationWorksForNestedReplies() {

        Publication other = persistPublication();
        Comment root = persist(Comment.onPublication(author, publication, "raiz"));
        Comment nested = persist(Comment.reply(author, root, "resposta"));

        assertThat(commentRepository.findByIdAndPublication_Id(nested.getId(), publication.getId())).isPresent();
        assertThat(commentRepository.findByIdAndPublication_Id(nested.getId(), other.getId())).isEmpty();
    }


    @Test
    void deletingACommentRemovesItsWholeSubtree() {

        Comment root = persist(Comment.onPublication(author, publication, "raiz"));
        Comment child = persist(Comment.reply(author, root, "filho"));
        Comment grandchild = persist(Comment.reply(author, child, "neto"));
        Comment sibling = persist(Comment.onPublication(author, publication, "outro"));
        entityManager.clear();

        commentRepository.delete(commentRepository.findById(root.getId()).orElseThrow());
        entityManager.flush();
        entityManager.clear();

        assertThat(commentRepository.findAllById(List.of(root.getId(), child.getId(), grandchild.getId()))).isEmpty();
        assertThat(commentRepository.findById(sibling.getId())).isPresent();
    }


    /*
     * A FK composta impede uma resposta "pendurada" em comentário
     * de outra publicação, mesmo com SQL direto.
     */
    @Test
    void databaseRejectsReplyPointingToParentOfAnotherPublication() {

        Publication other = persistPublication();
        Comment parent = persist(Comment.onPublication(author, publication, "pai"));

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("""
                        INSERT INTO comments (id, publication_id, parent_comment_id, channel_id, content, created_at)
                        VALUES (gen_random_uuid(), :otherPublication, :parent, :author, 'oi', now())
                        """)
                .setParameter("otherPublication", other.getId())
                .setParameter("parent", parent.getId())
                .setParameter("author", author.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("fk_comments_parent");
    }


    @Test
    void databaseRejectsBlankContent() {

        assertThatThrownBy(() -> persist(Comment.onPublication(author, publication, "   ")))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_comments_content_not_blank");
    }


    private Comment persist(Comment comment) {
        return entityManager.persistAndFlush(comment);
    }


    private void makeOlder(Comment comment) {

        entityManager.getEntityManager()
                .createNativeQuery("UPDATE comments SET created_at = created_at - INTERVAL '1 minute' WHERE id = :id")
                .setParameter("id", comment.getId())
                .executeUpdate();
    }


    private Channel persistUser(String username) {
        com.naro.user.domain.entity.User owner = entityManager.persist(new com.naro.user.domain.entity.User(UUID.randomUUID().toString(), username, null, null, null));
        return entityManager.persist(com.naro.support.Fixtures.channel(owner, username));
    }


    private Publication persistPublication() {
        return entityManager.persistAndFlush(new Publication(author, "content", "publications/" + UUID.randomUUID() + ".png"));
    }
}
