package com.naro.chat.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Aviso em tempo real (/user/queue/notifications), recebido em qualquer tela
 * do Naro. Só metadados: nunca o texto nem o ciphertext da mensagem, que
 * continuam apenas em /user/queue/chat (aberto só na área do chat).
 *
 *   CHAT_MESSAGE        nova mensagem numa conversa (também para o próprio
 *                       remetente, cujas outras abas/aparelhos reordenam a lista)
 *   CONVERSATION_READ   o próprio usuário leu a conversa, nesta ou em outra
 *                       aba/aparelho: o contador dela volta a zero
 */
public record ChatNotificationResponse(

        Type type,

        UUID conversationId,

        /* Só em CHAT_MESSAGE. */
        UUID messageId,

        /* Só em CHAT_MESSAGE. */
        UUID senderId,

        /* Só em CHAT_MESSAGE. */
        String senderUsername,

        /* Criação da mensagem, ou momento da leitura. */
        Instant createdAt

) {

    public enum Type {
        CHAT_MESSAGE,
        CONVERSATION_READ
    }

    public static ChatNotificationResponse message(ChatMessageResponse message) {
        return new ChatNotificationResponse(
                Type.CHAT_MESSAGE,
                message.conversationId(),
                message.id(),
                message.senderId(),
                message.senderUsername(),
                message.createdAt()
        );
    }

    public static ChatNotificationResponse read(UUID conversationId, Instant readAt) {
        return new ChatNotificationResponse(Type.CONVERSATION_READ, conversationId, null, null, null, readAt);
    }
}
