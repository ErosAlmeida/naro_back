package com.naro.chat.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendChatMessageRequest(

        @NotNull
        UUID conversationId,

        @NotBlank
        @Size(max = 4000)
        String content,

        /*
         * Opcional. Gerado pelo cliente e devolvido no evento
         * em tempo real, para ligar a mensagem otimista
         * ("enviando...") à mensagem persistida.
         * Não é persistido.
         */
        UUID clientMessageId

) {
}
