package com.naro.publication.rest;

import com.naro.channel.rest.ChannelController;
import com.naro.publication.application.dto.LikeResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de um like (quem curtiu):
 *
 *   channel   GET /api/v1/channels/{channelId}
 */
@Component
public class LikeModelAssembler
        implements RepresentationModelAssembler<LikeResponse, EntityModel<LikeResponse>> {

    @Override
    public EntityModel<LikeResponse> toModel(LikeResponse like) {
        return EntityModel.of(
                like,
                channel(like)
        );
    }

    private Link channel(LikeResponse like) {
        return linkTo(methodOn(ChannelController.class)
                .getById(like.channelId()))
                .withRel("channel");
    }
}
