package com.naro.publication.rest;

import com.naro.channel.rest.ChannelController;
import com.naro.publication.application.dto.PublicationResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de uma publicação:
 *
 *   self       GET /api/v1/publications/{id}
 *   author     GET /api/v1/channels/{authorId}
 *   comments   GET /api/v1/publications/{id}/comments
 *   likes      GET /api/v1/publications/{id}/likes
 *
 * As listas apontam para a primeira página (page=0, size=20).
 *
 * methodOn só lê o que vai na URL: usuário, X-Channel-Id e o
 * PagedResourcesAssembler vão como null.
 */
@Component
public class PublicationModelAssembler
        implements RepresentationModelAssembler<PublicationResponse, EntityModel<PublicationResponse>> {

    @Override
    public EntityModel<PublicationResponse> toModel(PublicationResponse publication) {
        return EntityModel.of(
                publication,
                self(publication),
                author(publication),
                comments(publication),
                likes(publication)
        );
    }

    private Link self(PublicationResponse publication) {
        return linkTo(methodOn(PublicationController.class)
                .getById(publication.id(), null, null))
                .withSelfRel();
    }

    private Link author(PublicationResponse publication) {
        return linkTo(methodOn(ChannelController.class)
                .getById(publication.author().id()))
                .withRel(IanaLinkRelations.AUTHOR);
    }

    private Link comments(PublicationResponse publication) {
        return linkTo(methodOn(CommentController.class)
                .getPublicationComments(publication.id(), 0, 20, null, null, null))
                .withRel("comments");
    }

    private Link likes(PublicationResponse publication) {
        return linkTo(methodOn(LikeController.class)
                .getPublicationLikes(publication.id(), 0, 20, null))
                .withRel("likes");
    }
}
