package com.naro.channel.application.dto;

import java.time.Instant;
import java.util.UUID;

public record FollowResponse(
        UUID id,
        UUID followerChannelId,
        UUID followingChannelId,
        boolean favorite,
        long interactionCount,
        Instant lastInteractionAt,
        Double relevanceScore,
        Instant createdAt
) {
}
