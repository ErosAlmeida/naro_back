package com.naro.publication.application.dto;

import com.naro.channel.application.dto.ChannelSummaryResponse;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

/**
 * Um comentário de qualquer nível. As respostas NÃO vêm aninhadas:
 * replyCount indica quantas existem e elas são buscadas paginadas em
 * GET /api/v1/comments/{id}/comments.
 */
@Relation(collectionRelation = "comments")
public record CommentResponse(

        UUID id,

        UUID publicationId,

        /* null para comentários diretos na publicação */
        UUID parentCommentId,

        String content,

        ChannelSummaryResponse author,

        long likeCount,

        boolean likedByCurrentChannel,

        /* respostas diretas */
        long replyCount,

        Instant createdAt

) {
}
