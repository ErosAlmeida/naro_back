package com.naro.chat.application.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Envio pelo WebSocket (/app/chat/send). Não carrega identidade:
 * o remetente é o usuário da conexão.
 *
 * Duas formas, conferidas pela ChatMessagePayloadPolicy conforme
 * as feature flags do E2EE:
 *
 *   { conversationId, ciphertext, iv, encryptionVersion }   E2EE
 *   { conversationId, content }                              legado
 *
 * Com naro.security.e2ee.required=true só a primeira é aceita.
 * Os tamanhos são validados pela policy, que devolve o erro
 * correlacionado com clientMessageId.
 */
public record SendChatMessageRequest(

        @NotNull
        UUID conversationId,

        String content,

        String ciphertext,

        String iv,

        Integer encryptionVersion,

        /*
         * Opcional. Gerado pelo cliente e devolvido no evento
         * em tempo real, para ligar a mensagem otimista
         * ("enviando...") à mensagem persistida.
         * Não é persistido.
         */
        UUID clientMessageId

) {

    /** Mensagem sem criptografia (formato legado). */
    public SendChatMessageRequest(UUID conversationId, String content, UUID clientMessageId) {
        this(conversationId, content, null, null, null, clientMessageId);
    }

    public static SendChatMessageRequest encrypted(
            UUID conversationId,
            String ciphertext,
            String iv,
            Integer encryptionVersion,
            UUID clientMessageId
    ) {
        return new SendChatMessageRequest(conversationId, null, ciphertext, iv, encryptionVersion, clientMessageId);
    }
}
