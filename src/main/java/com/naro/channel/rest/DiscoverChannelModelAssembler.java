package com.naro.channel.rest;

import com.naro.channel.application.dto.DiscoverChannelResponse;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@Component
public class DiscoverChannelModelAssembler
        implements RepresentationModelAssembler<DiscoverChannelResponse, EntityModel<DiscoverChannelResponse>> {

    @Override
    public EntityModel<DiscoverChannelResponse> toModel(DiscoverChannelResponse channel) {
        return EntityModel.of(
                channel,
                linkTo(methodOn(ChannelController.class).getById(channel.id())).withSelfRel()
        );
    }
}
