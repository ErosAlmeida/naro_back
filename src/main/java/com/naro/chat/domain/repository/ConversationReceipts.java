package com.naro.chat.domain.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Marcadores de entrega e leitura dos outros participantes de uma conversa,
 * do ponto de vista de um usuário. Num grupo vale o de quem está mais atrás;
 * null enquanto algum participante não tem o marcador.
 */
public interface ConversationReceipts {
    UUID getConversationId();
    Instant getDeliveredAt();
    Instant getReadAt();
}
