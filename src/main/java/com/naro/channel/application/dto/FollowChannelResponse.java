package com.naro.channel.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.util.UUID;

@Relation(collectionRelation = "followChannels")
public record FollowChannelResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String profileImageUrl
) {
}
