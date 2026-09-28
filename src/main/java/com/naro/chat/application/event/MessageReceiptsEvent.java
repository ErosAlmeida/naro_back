package com.naro.chat.application.event;

import com.naro.chat.application.dto.MessageReceipts;

import java.time.Instant;
import java.util.UUID;

/**
 * As mensagens de um participante chegaram ou foram lidas pelos outros.
 * Vai só para as sessões dele (os tiques mudam em todos os aparelhos).
 */
public record MessageReceiptsEvent(

        UUID conversationId,

        String recipientKeycloakId,

        MessageReceipts receipts,

        Instant at

) {
}
