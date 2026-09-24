package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.dto.ConversationResponse;

import com.naro.chat.application.service.ChatService;

import com.naro.config.CurrentUser;

import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;

import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Chats", description = "Conversas privadas; as mensagens novas chegam pelo WebSocket")
@RestController
@RequestMapping("/api/v1/chats")
public class ChatRestController {

    private final ChatService chatService;

    private final ConversationModelAssembler conversationAssembler;


    public ChatRestController(
            ChatService chatService,
            ConversationModelAssembler conversationAssembler
    ) {
        this.chatService = chatService;
        this.conversationAssembler = conversationAssembler;
    }


    @PostMapping("/direct/{userId}")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Abre (ou reaproveita) a conversa direta com outro usuário")
    public ResponseEntity<EntityModel<ConversationResponse>>
    createDirectConversation(

            @PathVariable
            UUID userId,

            @CurrentUser
            User currentUser

    ) {

        ConversationResponse response =
                chatService
                        .createDirectConversation(
                                userId,
                                currentUser
                        );


        URI location =
                URI.create(
                        "/api/v1/chats/"
                                + response.id()
                );


        return ResponseEntity
                .created(location)
                .body(conversationAssembler.toModel(response));
    }


    @GetMapping
    @Operation(summary = "Lista as conversas do usuário autenticado, as mais ativas primeiro")
    public CollectionModel<EntityModel<ConversationResponse>>
    getConversations(

            @CurrentUser
            User currentUser

    ) {

        return conversationAssembler
                .toCollectionModel(
                        chatService.getConversations(
                                currentUser
                        )
                );
    }


    @GetMapping("/{conversationId}/messages")
    @Operation(summary = "Histórico de mensagens de uma conversa, mais recentes primeiro")
    public PagedModel<EntityModel<ChatMessageResponse>>
    getMessages(

            @PathVariable
            UUID conversationId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "50"
            )
            int size,

            @CurrentUser
            User currentUser,

            PagedResourcesAssembler<ChatMessageResponse> pagedAssembler

    ) {

        int safePage =
                Math.max(
                        page,
                        0
                );


        int safeSize =
                Math.clamp(
                        size,
                        1,
                        100
                );


        /*
         * Sem assembler de mensagem: cada mensagem vai sem links,
         * a página traz só a navegação (first, next...).
         */
        return pagedAssembler.toModel(
                chatService.getMessages(
                        conversationId,
                        currentUser,
                        PageRequest.of(
                                safePage,
                                safeSize
                        )
                )
        );
    }
}