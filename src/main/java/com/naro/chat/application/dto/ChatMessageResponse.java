package com.naro.chat.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma mensagem é de uma das duas formas:
 *
 *   encrypted = false   mensagem anterior ao E2EE, texto em content
 *   encrypted = true    content null; ciphertext, iv e encryptionVersion
 *                       são repassados como chegaram, e só o browser decifra
 */
@Relation(collectionRelation = "messages")
public record ChatMessageResponse(

        UUID id,

        UUID conversationId,

        UUID senderId,

        String senderUsername,

        String content,

        boolean encrypted,

        String ciphertext,

        String iv,

        Integer encryptionVersion,

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
                encrypted,
                ciphertext,
                iv,
                encryptionVersion,
                createdAt,
                clientMessageId
        );
    }
}
