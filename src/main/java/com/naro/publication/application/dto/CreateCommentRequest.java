package com.naro.publication.application.dto;

import com.naro.publication.domain.entity.Comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Usado para comentar uma publicação e para responder um comentário.
 * Não há authorId: o autor é sempre o canal selecionado.
 */
public record CreateCommentRequest(

        @NotBlank
        @Size(max = Comment.MAX_CONTENT_LENGTH)
        String content

) {
}
