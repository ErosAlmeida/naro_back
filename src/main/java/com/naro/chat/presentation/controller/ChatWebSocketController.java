package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatErrorResponse;
import com.naro.chat.application.dto.SendChatMessageRequest;

import com.naro.chat.application.service.ChatService;

import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;

import org.springframework.messaging.simp.annotation.SendToUser;

import org.springframework.security.access.AccessDeniedException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.stereotype.Controller;

import java.time.Instant;

@Controller
public class ChatWebSocketController {

    private final ChatService chatService;

    private final CurrentUserService currentUserService;


    public ChatWebSocketController(
            ChatService chatService,
            CurrentUserService currentUserService
    ) {
        this.chatService = chatService;
        this.currentUserService =
                currentUserService;
    }


    @MessageMapping("/chat/send")
    public void sendMessage(

            @Valid
            @Payload
            SendChatMessageRequest request,

            @AuthenticationPrincipal
            Jwt jwt

    ) {

        User currentUser =
                currentUserService.resolve(jwt);


        chatService.sendMessage(
                request,
                currentUser
        );
    }


    @MessageExceptionHandler({
            IllegalArgumentException.class,
            EntityNotFoundException.class,
            AccessDeniedException.class
    })
    @SendToUser(
            destinations = "/queue/errors",
            broadcast = false
    )
    public ChatErrorResponse handleChatError(
            RuntimeException exception
    ) {

        return new ChatErrorResponse(
                "CHAT_ERROR",
                exception.getMessage(),
                Instant.now()
        );
    }
}