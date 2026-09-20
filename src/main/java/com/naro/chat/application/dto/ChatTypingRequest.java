package com.naro.chat.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Não possui userId: quem está digitando é sempre
 * o usuário autenticado na conexão.
 */
public record ChatTypingRequest(

        @NotNull
        UUID conversationId,

        boolean typing

) {
}
