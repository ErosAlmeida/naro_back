package com.naro.chat.application.service;

import com.naro.chat.application.dto.SendChatMessageRequest;
import com.naro.encryption.application.service.E2eeFormat;
import com.naro.encryption.config.E2eeProperties;
import com.naro.exception.E2eeError;
import com.naro.exception.E2eeException;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Decide se uma mensagem nova pode ser aceita, conforme as flags do E2EE.
 *
 * Com naro.security.e2ee.required=true não existe caminho de volta para
 * plaintext: qualquer mensagem com content é recusada com E2EE_REQUIRED,
 * mesmo que também traga ciphertext. Nada do payload recusado é
 * persistido, publicado ou registrado em log.
 */
@Component
public class ChatMessagePayloadPolicy {

    /** Mesmo limite de antes para o texto das mensagens legadas. */
    static final int MAX_CONTENT_LENGTH = 4000;

    /**
     * 4000 caracteres UTF-16 viram no máximo 12000 bytes em UTF-8;
     * com a tag GCM e o Base64, ficam abaixo deste limite.
     */
    static final int MAX_CIPHERTEXT_LENGTH = 16_384;

    private final E2eeProperties properties;

    public ChatMessagePayloadPolicy(E2eeProperties properties) {
        this.properties = properties;
    }

    ChatMessagePayload validate(SendChatMessageRequest request) {
        boolean hasPlaintext = request.content() != null;
        boolean hasEncryption = request.ciphertext() != null
                || request.iv() != null
                || request.encryptionVersion() != null;

        if (properties.required()) {
            if (hasPlaintext || !hasEncryption) {
                throw rejected(E2eeError.REQUIRED, request);
            }
            return encrypted(request);
        }

        if (hasPlaintext && hasEncryption) {
            throw rejected(E2eeError.INVALID_PAYLOAD, request);
        }

        if (hasEncryption) {
            if (!properties.available()) {
                throw rejected(E2eeError.UNAVAILABLE, request);
            }
            return encrypted(request);
        }

        String content = request.content() == null ? "" : request.content().trim();

        if (content.isEmpty() || request.content().length() > MAX_CONTENT_LENGTH) {
            throw rejected(E2eeError.INVALID_PAYLOAD, request);
        }

        return new ChatMessagePayload.Plaintext(content);
    }

    private static ChatMessagePayload encrypted(SendChatMessageRequest request) {
        String ciphertext = request.ciphertext();

        if (ciphertext == null || ciphertext.length() > MAX_CIPHERTEXT_LENGTH) {
            throw rejected(E2eeError.INVALID_PAYLOAD, request);
        }

        try {
            E2eeFormat.requireVersion(request.encryptionVersion());
            E2eeFormat.requireIv(request.iv());

            // Ao menos um byte cifrado além da tag de autenticação.
            if (E2eeFormat.decodeBase64(ciphertext).length <= E2eeFormat.GCM_TAG_BYTES) {
                throw rejected(E2eeError.INVALID_PAYLOAD, request);
            }
        } catch (E2eeException exception) {
            throw rejected(exception.getError(), request);
        }

        return new ChatMessagePayload.Encrypted(ciphertext, request.iv(), request.encryptionVersion());
    }

    private static E2eeException rejected(E2eeError error, SendChatMessageRequest request) {
        UUID conversationId = request.conversationId();
        return new E2eeException(error, conversationId, request.clientMessageId());
    }
}
