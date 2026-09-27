package com.naro.chat.application.service;

/**
 * Conteúdo de uma mensagem já validado pela ChatMessagePayloadPolicy.
 */
sealed interface ChatMessagePayload {

    /** Formato legado: só existe com naro.security.e2ee.required=false. */
    record Plaintext(String content) implements ChatMessagePayload {
    }

    /** Guardado e repassado exatamente como chegou; nunca decifrado aqui. */
    record Encrypted(String ciphertext, String iv, int version) implements ChatMessagePayload {
    }
}
