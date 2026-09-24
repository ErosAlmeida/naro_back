package com.naro.channel.rest;

import com.naro.channel.application.dto.FollowChannelResponse;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@Component
public class FollowChannelModelAssembler
        implements RepresentationModelAssembler<FollowChannelResponse, EntityModel<FollowChannelResponse>> {

    @Override
    public EntityModel<FollowChannelResponse> toModel(FollowChannelResponse channel) {
        return EntityModel.of(
                channel,
                linkTo(methodOn(ChannelController.class).getById(channel.id())).withSelfRel()
        );
    }
}
