package com.naro.publication.application.dto;

import com.naro.channel.application.dto.ChannelSummaryResponse;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

@Relation(collectionRelation = "publications")
public record PublicationResponse(

        UUID id,

        /* canal autor: id, nome e slug, como em CommentResponse */
        ChannelSummaryResponse author,

        String content,

        String imageUrl,

        Instant createdAt,

        long likeCount,

        long commentCount,

        boolean likedByCurrentChannel

) {
}
