package com.naro.channel.rest;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.publication.rest.PublicationController;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de um canal:
 *
 *   self           GET /api/v1/channels/{id}
 *   publications   GET /api/v1/publications?authorId={id}
 *
 * A lista aponta para a primeira página (page=0, size=20).
 *
 * methodOn só lê o que vai na URL: usuário, X-Channel-Id e o
 * PagedResourcesAssembler vão como null.
 */
@Component
public class ChannelModelAssembler
        implements RepresentationModelAssembler<ChannelResponse, EntityModel<ChannelResponse>> {

    @Override
    public EntityModel<ChannelResponse> toModel(ChannelResponse channel) {
        return EntityModel.of(
                channel,
                self(channel),
                publications(channel)
        );
    }

    private Link self(ChannelResponse channel) {
        return linkTo(methodOn(ChannelController.class)
                .getById(channel.id()))
                .withSelfRel();
    }

    private Link publications(ChannelResponse channel) {
        return linkTo(methodOn(PublicationController.class)
                .getPublications(channel.id(), 0, 20, null, null, null))
                .withRel("publications");
    }
}
