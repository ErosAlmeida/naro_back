package com.naro.friend.application.dto;

import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.user.application.dto.UserSummaryResponse;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

@Relation(collectionRelation = "friendRequests")
public record FriendRequestResponse(
        UUID id,
        UserSummaryResponse sender,
        UserSummaryResponse receiver,
        FriendRequestStatus status,
        Instant createdAt,
        Instant respondedAt
) {
}
