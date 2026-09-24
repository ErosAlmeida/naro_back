package com.naro.publication.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

@Relation(collectionRelation = "likes")
public record LikeResponse(

        UUID channelId,

        String slug,

        Instant createdAt

) {
}
