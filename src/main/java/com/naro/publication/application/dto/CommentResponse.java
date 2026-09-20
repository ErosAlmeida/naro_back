package com.naro.publication.application.dto;

import com.naro.user.application.dto.UserSummaryResponse;

import java.time.Instant;
import java.util.UUID;

/**
 * Um comentário de qualquer nível. As respostas NÃO vêm aninhadas:
 * replyCount indica quantas existem e elas são buscadas paginadas em
 * GET /api/v1/comments/{id}/comments.
 */
public record CommentResponse(

        UUID id,

        UUID publicationId,

        /* null para comentários diretos na publicação */
        UUID parentCommentId,

        String content,

        UserSummaryResponse author,

        long likeCount,

        boolean likedByCurrentUser,

        /* respostas diretas */
        long replyCount,

        Instant createdAt

) {
}
