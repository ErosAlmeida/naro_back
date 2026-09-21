package com.naro.support;

import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Publication;
import com.naro.user.domain.entity.User;
import com.naro.channel.domain.entity.Channel;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidades "já persistidas" (com id) para testes unitários,
 * sem banco. As entidades não expõem setters de id de propósito.
 */
public final class Fixtures {

    private Fixtures() {
    }


    public static User user(String username) {

        User user = new User("kc-" + username, username, username + "@naro.dev", null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }


    public static Channel channel(String slug) {
        Channel channel = channel(user(slug), slug);
        return persisted(channel);
    }

    public static Channel channel(User owner, String slug) {
        Channel channel = new Channel();
        channel.setOwner(owner);
        channel.setName(slug);
        channel.setSlug(slug);
        return channel;
    }

    public static Publication publication(Channel author) {
        return persisted(new Publication(author, "content", "publications/" + UUID.randomUUID() + ".png"));
    }


    public static <T> T persisted(T entity) {

        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entity, "createdAt", Instant.now());

        return entity;
    }


    public static Comment comment(Channel author, Publication publication, String content) {
        return persisted(Comment.onPublication(author, publication, content));
    }


    public static Comment reply(Channel author, Comment parent, String content) {
        return persisted(Comment.reply(author, parent, content));
    }
}
