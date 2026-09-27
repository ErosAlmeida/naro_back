package com.naro.chat.domain.repository;

import java.util.UUID;

/** Mensagens não lidas de uma conversa, contadas no banco sem carregar mensagens. */
public interface ConversationUnreadCount {
    UUID getConversationId();
    long getUnread();
}
