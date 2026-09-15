package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.dto.ConversationResponse;

import com.naro.chat.application.service.ChatService;

import com.naro.config.CurrentUser;

import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chats")
public class ChatRestController {

    private final ChatService chatService;


    public ChatRestController(
            ChatService chatService
    ) {
        this.chatService = chatService;
    }


    @PostMapping("/direct/{userId}")
    public ResponseEntity<ConversationResponse>
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
                .body(response);
    }


    @GetMapping
    public List<ConversationResponse>
    getConversations(

            @CurrentUser
            User currentUser

    ) {

        return chatService
                .getConversations(
                        currentUser
                );
    }


    @GetMapping("/{conversationId}/messages")
    public Page<ChatMessageResponse>
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
            User currentUser

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


        return chatService.getMessages(
                conversationId,
                currentUser,
                PageRequest.of(
                        safePage,
                        safeSize
                )
        );
    }
}