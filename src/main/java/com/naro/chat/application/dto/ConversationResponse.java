package com.naro.chat.application.dto;

import com.naro.chat.domain.enums.ConversationType;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Relation(collectionRelation = "conversations")
public record ConversationResponse(

        UUID id,

        ConversationType type,

        Set<ChatParticipantResponse> participants,

        Instant createdAt,

        Instant updatedAt,

        /*
         * null quando a conversa ainda não tem mensagens.
         */
        ChatMessageResponse lastMessage,

        /* Histórico permanece visível quando a amizade termina. */
        boolean canMessage,

        /*
         * Mensagens de outras pessoas depois da última leitura do usuário
         * autenticado. Preenchido na lista (GET /api/v1/chats); 0 nas demais
         * respostas.
         */
        long unreadCount,

        /*
         * Tiques das mensagens do usuário autenticado: até onde os outros
         * participantes receberam e leram. Nunca null (MessageReceipts.NONE).
         */
        MessageReceipts receipts

) {
    public ConversationResponse {
        receipts = receipts == null ? MessageReceipts.NONE : receipts;
    }

    public ConversationResponse(UUID id, ConversationType type,
                                Set<ChatParticipantResponse> participants,
                                Instant createdAt, Instant updatedAt,
                                ChatMessageResponse lastMessage) {
        this(id, type, participants, createdAt, updatedAt, lastMessage, false, 0, MessageReceipts.NONE);
    }

    public ConversationResponse withUnreadCount(long unreadCount) {
        return new ConversationResponse(id, type, participants, createdAt, updatedAt, lastMessage, canMessage, unreadCount, receipts);
    }

    public ConversationResponse withReceipts(MessageReceipts receipts) {
        return new ConversationResponse(id, type, participants, createdAt, updatedAt, lastMessage, canMessage, unreadCount, receipts);
    }
}
