package com.naro.chat.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * O leitor marcou a conversa como lida. Vai só para as sessões dele
 * (outras abas e aparelhos zeram o contador da conversa).
 */
public record ConversationReadEvent(

        UUID conversationId,

        String readerKeycloakId,

        Instant readAt

) {
}
