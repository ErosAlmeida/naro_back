package com.naro.friend.application.dto;

import com.naro.friend.domain.enums.RelationshipStatus;

import java.util.UUID;

/** Resolves an actionable request even when it is outside a list's current page. */
public record FriendRelationshipResponse(RelationshipStatus status, UUID requestId) {
}
