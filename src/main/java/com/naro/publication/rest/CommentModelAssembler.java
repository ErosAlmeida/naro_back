package com.naro.publication.rest;

import com.naro.channel.rest.ChannelController;
import com.naro.publication.application.dto.CommentResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de um comentário (de qualquer nível):
 *
 *   publication   GET /api/v1/publications/{publicationId}
 *   author        GET /api/v1/channels/{authorId}
 *   replies       GET /api/v1/comments/{id}/comments
 *   likes         GET /api/v1/comments/{id}/likes
 *
 * As listas apontam para a primeira página (page=0, size=20).
 * Não há self: um comentário não tem GET próprio.
 *
 * methodOn só lê o que vai na URL: usuário, X-Channel-Id e o
 * PagedResourcesAssembler vão como null.
 */
@Component
public class CommentModelAssembler
        implements RepresentationModelAssembler<CommentResponse, EntityModel<CommentResponse>> {

    @Override
    public EntityModel<CommentResponse> toModel(CommentResponse comment) {
        return EntityModel.of(
                comment,
                publication(comment),
                author(comment),
                replies(comment),
                likes(comment)
        );
    }

    private Link publication(CommentResponse comment) {
        return linkTo(methodOn(PublicationController.class)
                .getById(comment.publicationId(), null, null))
                .withRel("publication");
    }

    private Link author(CommentResponse comment) {
        return linkTo(methodOn(ChannelController.class)
                .getById(comment.author().id()))
                .withRel(IanaLinkRelations.AUTHOR);
    }

    private Link replies(CommentResponse comment) {
        return linkTo(methodOn(CommentController.class)
                .getReplies(comment.id(), 0, 20, null, null, null))
                .withRel("replies");
    }

    private Link likes(CommentResponse comment) {
        return linkTo(methodOn(LikeController.class)
                .getCommentLikes(comment.id(), 0, 20, null))
                .withRel("likes");
    }
}
