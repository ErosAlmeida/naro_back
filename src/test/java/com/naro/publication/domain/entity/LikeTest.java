package com.naro.publication.domain.entity;

import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.Test;

import static com.naro.support.Fixtures.comment;
import static com.naro.support.Fixtures.publication;
import static com.naro.support.Fixtures.reply;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LikeTest {

    private final User leonardo = user("leonardo");

    private final Publication publication = publication(user("maria"));


    @Test
    void likeOnPublicationHasNoComment() {

        Like like = Like.publication(leonardo, publication);

        assertThat(like.getUser()).isSameAs(leonardo);
        assertThat(like.getPublication()).isSameAs(publication);
        assertThat(like.getComment()).isNull();
    }


    @Test
    void likeOnCommentHasNoPublication() {

        Comment comment = comment(leonardo, publication, "A");

        Like like = Like.comment(leonardo, comment);

        assertThat(like.getUser()).isSameAs(leonardo);
        assertThat(like.getComment()).isSameAs(comment);
        assertThat(like.getPublication()).isNull();
    }


    @Test
    void likeOnNestedReplyPointsOnlyToTheReply() {

        Comment nested = reply(leonardo, reply(leonardo, comment(leonardo, publication, "A"), "B"), "C");

        Like like = Like.comment(leonardo, nested);

        assertThat(like.getComment()).isSameAs(nested);
        assertThat(like.getPublication()).isNull();
    }


    @Test
    void likeAlwaysNeedsItsPublicationOrComment() {

        assertThatThrownBy(() -> Like.publication(leonardo, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> Like.comment(leonardo, null))
                .isInstanceOf(NullPointerException.class);
    }
}
