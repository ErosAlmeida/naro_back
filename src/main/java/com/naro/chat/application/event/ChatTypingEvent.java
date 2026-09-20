package com.naro.chat.application.event;

import com.naro.chat.application.dto.ChatTypingResponse;

import java.util.Set;

/**
 * Evento efêmero: nunca é persistido.
 */
public record ChatTypingEvent(

        ChatTypingResponse typing,

        Set<String> recipientKeycloakIds

) {
}
