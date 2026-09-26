package com.naro.friend.application.dto;

import com.naro.friend.domain.enums.RelationshipStatus;
import org.springframework.hateoas.server.core.Relation;

import java.util.UUID;

@Relation(collectionRelation = "people")
public record FriendDiscoveryResponse(
        UUID id,
        String username,
        String firstName,
        String lastName,
        String avatarUrl,
        RelationshipStatus relationshipStatus,
        long mutualFriendsCount
) {
}
