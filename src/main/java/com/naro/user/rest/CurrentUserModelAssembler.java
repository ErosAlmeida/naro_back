package com.naro.user.rest;

import com.naro.channel.rest.ChannelController;
import com.naro.chat.presentation.controller.ChatRestController;
import com.naro.user.application.dto.CurrentUserResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * O usuário autenticado é a porta de entrada da API:
 * a partir dele se chega ao que é dele.
 *
 *   self            GET /api/users/me
 *   channels        GET /api/v1/channels/me
 *   conversations   GET /api/v1/chats
 *
 * methodOn só lê o que vai na URL: o usuário vai como null.
 */
@Component
public class CurrentUserModelAssembler
        implements RepresentationModelAssembler<CurrentUserResponse, EntityModel<CurrentUserResponse>> {

    @Override
    public EntityModel<CurrentUserResponse> toModel(CurrentUserResponse user) {
        return EntityModel.of(
                user,
                self(),
                channels(),
                conversations()
        );
    }

    private Link self() {
        return linkTo(methodOn(UserController.class)
                .me(null))
                .withSelfRel();
    }

    private Link channels() {
        return linkTo(methodOn(ChannelController.class)
                .getMyChannels(null))
                .withRel("channels");
    }

    private Link conversations() {
        return linkTo(methodOn(ChatRestController.class)
                .getConversations(null))
                .withRel("conversations");
    }
}
