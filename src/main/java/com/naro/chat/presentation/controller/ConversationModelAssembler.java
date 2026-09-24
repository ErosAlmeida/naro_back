package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ConversationResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de uma conversa:
 *
 *   messages   GET /api/v1/chats/{id}/messages
 *
 * A lista aponta para a primeira página (page=0, size=50).
 * Não há self: uma conversa não tem GET próprio.
 *
 * methodOn só lê o que vai na URL: o usuário e o
 * PagedResourcesAssembler vão como null.
 */
@Component
public class ConversationModelAssembler
        implements RepresentationModelAssembler<ConversationResponse, EntityModel<ConversationResponse>> {

    @Override
    public EntityModel<ConversationResponse> toModel(ConversationResponse conversation) {
        return EntityModel.of(
                conversation,
                messages(conversation)
        );
    }

    private Link messages(ConversationResponse conversation) {
        return linkTo(methodOn(ChatRestController.class)
                .getMessages(conversation.id(), 0, 50, null, null))
                .withRel("messages");
    }
}
