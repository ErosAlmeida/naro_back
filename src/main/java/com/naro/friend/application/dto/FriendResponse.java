package com.naro.friend.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

@Relation(collectionRelation = "friends")
public record FriendResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String avatarUrl,
        Instant friendsSince
) {
}
