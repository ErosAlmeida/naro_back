package com.naro.chat.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

@Relation(collectionRelation = "messages")
public record ChatMessageResponse(

        UUID id,

        UUID conversationId,

        UUID senderId,

        String senderUsername,

        String content,

        Instant createdAt,

        /*
         * Preenchido apenas no evento em tempo real enviado
         * após o envio; null no histórico.
         */
        UUID clientMessageId

) {

    public ChatMessageResponse withClientMessageId(
            UUID clientMessageId
    ) {

        return new ChatMessageResponse(
                id,
                conversationId,
                senderId,
                senderUsername,
                content,
                createdAt,
                clientMessageId
        );
    }
}
