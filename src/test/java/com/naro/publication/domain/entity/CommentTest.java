package com.naro.publication.domain.entity;

import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.Test;

import static com.naro.support.Fixtures.publication;
import static com.naro.support.Fixtures.persisted;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * Publication
 *  └── A           Comment.onPublication
 *       └── B      Comment.reply
 *            └── C Comment.reply
 */
class CommentTest {

    private final User leonardo = user("leonardo");

    private final User maria = user("maria");

    private final Publication publication = publication(maria);


    @Test
    void commentOnPublicationIsARootComment() {

        Comment a = Comment.onPublication(leonardo, publication, "A");

        assertThat(a.getPublication()).isSameAs(publication);
        assertThat(a.getParentComment()).isNull();
        assertThat(a.getAuthor()).isSameAs(leonardo);
        assertThat(a.getContent()).isEqualTo("A");
        assertThat(a.isRootComment()).isTrue();
        assertThat(a.isReply()).isFalse();
    }


    @Test
    void replyPointsToItsParentAndInheritsThePublication() {

        Comment a = persisted(Comment.onPublication(leonardo, publication, "A"));

        Comment b = Comment.reply(maria, a, "B");

        assertThat(b.getParentComment()).isSameAs(a);
        assertThat(b.getPublication()).isSameAs(publication);
        assertThat(b.getAuthor()).isSameAs(maria);
        assertThat(b.isReply()).isTrue();
        assertThat(b.isRootComment()).isFalse();
    }


    @Test
    void replyOfReplyStillBelongsToTheOriginalPublication() {

        Comment a = persisted(Comment.onPublication(leonardo, publication, "A"));
        Comment b = persisted(Comment.reply(maria, a, "B"));

        Comment c = Comment.reply(leonardo, b, "C");

        assertThat(c.getParentComment()).isSameAs(b);
        assertThat(c.getPublication()).isSameAs(publication);
    }


    @Test
    void isAuthoredByComparesTheUserId() {

        Comment a = Comment.onPublication(leonardo, publication, "A");

        assertThat(a.isAuthoredBy(leonardo)).isTrue();
        assertThat(a.isAuthoredBy(maria)).isFalse();
    }


    @Test
    void cannotBeCreatedWithoutPublicationOrParent() {

        assertThatThrownBy(() -> Comment.onPublication(leonardo, null, "A"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Comment.reply(leonardo, null, "B"))
                .isInstanceOf(NullPointerException.class);
    }
}
