package com.naro.chat.domain.repository;

import java.time.Instant;
import java.util.UUID;

/** Conversa com mensagens de outras pessoas ainda não entregues ao usuário, e a mais nova delas. */
public interface PendingDelivery {
    UUID getConversationId();
    Instant getLatestAt();
}
