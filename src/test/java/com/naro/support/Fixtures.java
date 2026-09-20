package com.naro.support;

import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Publication;
import com.naro.user.domain.entity.User;

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


    public static Publication publication(User author) {
        return persisted(new Publication(author, "content", "publications/" + UUID.randomUUID() + ".png"));
    }


    public static <T> T persisted(T entity) {

        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entity, "createdAt", Instant.now());

        return entity;
    }


    public static Comment comment(User author, Publication publication, String content) {
        return persisted(Comment.onPublication(author, publication, content));
    }


    public static Comment reply(User author, Comment parent, String content) {
        return persisted(Comment.reply(author, parent, content));
    }
}
