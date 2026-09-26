package com.naro.chat.presentation.controller;

import com.naro.chat.application.dto.ChatErrorResponse;
import com.naro.chat.application.dto.ChatTypingRequest;
import com.naro.chat.application.dto.SendChatMessageRequest;

import com.naro.chat.application.service.ChatService;
import com.naro.exception.ChatFriendshipRequiredException;

import com.naro.security.RealtimePrincipal;

import com.naro.user.application.service.CurrentUserService;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;

import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;

import org.springframework.messaging.simp.annotation.SendToUser;

import org.springframework.security.access.AccessDeniedException;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.stereotype.Controller;

import org.springframework.web.ErrorResponseException;

import java.time.Instant;

/**
 * A identidade de quem envia vem sempre do RealtimePrincipal
 * criado no CONNECT. Os payloads não carregam userId/senderId.
 */
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
            RealtimePrincipal principal

    ) {

        chatService.sendMessage(
                request,
                currentUserService.resolveRealtime(principal)
        );
    }


    @MessageMapping("/chat/typing")
    public void typing(

            @Valid
            @Payload
            ChatTypingRequest request,

            @AuthenticationPrincipal
            RealtimePrincipal principal

    ) {

        chatService.notifyTyping(
                request,
                currentUserService.resolveRealtime(principal)
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


    /*
     * NotFoundException, BadRequestException e ForbiddenException
     * carregam a mensagem no ProblemDetail.
     *
     * getMessage() traria status + ProblemDetail inteiro,
     * então o cliente recebe apenas o detail.
     */
    @MessageExceptionHandler(ErrorResponseException.class)
    @SendToUser(
            destinations = "/queue/errors",
            broadcast = false
    )
    public ChatErrorResponse handleErrorResponse(
            ErrorResponseException exception
    ) {

        if (exception instanceof ChatFriendshipRequiredException friendshipRequired) {
            return new ChatErrorResponse("FRIENDSHIP_REQUIRED", exception.getBody().getDetail(),
                    Instant.now(), friendshipRequired.getConversationId(), friendshipRequired.getClientMessageId());
        }

        return new ChatErrorResponse(
                "CHAT_ERROR",
                exception.getBody().getDetail(),
                Instant.now()
        );
    }


    /*
     * Falha de @Valid no payload STOMP (ex.: mensagem vazia).
     */
    @MessageExceptionHandler(MethodArgumentNotValidException.class)
    @SendToUser(
            destinations = "/queue/errors",
            broadcast = false
    )
    public ChatErrorResponse handleInvalidPayload(
            MethodArgumentNotValidException exception
    ) {

        return new ChatErrorResponse(
                "INVALID_PAYLOAD",
                "Invalid message payload",
                Instant.now()
        );
    }
}
