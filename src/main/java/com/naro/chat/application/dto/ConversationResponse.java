package com.naro.chat.application.dto;

import com.naro.chat.domain.enums.ConversationType;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ConversationResponse(

        UUID id,

        ConversationType type,

        Set<ChatParticipantResponse> participants,

        Instant createdAt,

        Instant updatedAt,

        /*
         * null quando a conversa ainda não tem mensagens.
         */
        ChatMessageResponse lastMessage

) {
}
