package com.naro.chat.application.event;

import com.naro.chat.application.dto.ChatMessageResponse;

import java.util.Set;

public record ChatMessageCreatedEvent(

        ChatMessageResponse message,

        Set<String> recipientKeycloakIds

) {
}