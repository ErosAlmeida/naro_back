package com.naro.chat.application.service;

import com.naro.chat.application.dto.SendChatMessageRequest;
import com.naro.encryption.config.E2eeProperties;
import com.naro.exception.E2eeError;
import com.naro.exception.E2eeException;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatMessagePayloadPolicyTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatMessagePayloadPolicy required = new ChatMessagePayloadPolicy(new E2eeProperties(true, true));

    private final ChatMessagePayloadPolicy available = new ChatMessagePayloadPolicy(new E2eeProperties(true, false));

    private final ChatMessagePayloadPolicy disabled = new ChatMessagePayloadPolicy(new E2eeProperties(false, false));

    private final UUID conversationId = UUID.randomUUID();

    private final UUID clientMessageId = UUID.randomUUID();

    @Test
    void requiredModeRejectsPlaintextWithCorrelation() {
        assertRejected(required, new SendChatMessageRequest(conversationId, "Olá", clientMessageId), E2eeError.REQUIRED);
    }

    @Test
    void requiredModeRejectsPlaintextEvenWhenCiphertextIsAlsoPresent() {
        var mixed = new SendChatMessageRequest(conversationId, "Olá", bytes(40), bytes(12), 1, clientMessageId);

        assertRejected(required, mixed, E2eeError.REQUIRED);
    }

    @Test
    void requiredModeRejectsEmptyPlaintextAndMissingEncryption() {
        assertRejected(required, new SendChatMessageRequest(conversationId, "", clientMessageId), E2eeError.REQUIRED);
        assertRejected(required, new SendChatMessageRequest(conversationId, null, clientMessageId), E2eeError.REQUIRED);
    }

    @Test
    void requiredModeAcceptsCiphertextUnchanged() {
        String ciphertext = bytes(40);
        String iv = bytes(12);

        ChatMessagePayload payload = required.validate(
                SendChatMessageRequest.encrypted(conversationId, ciphertext, iv, 1, clientMessageId));

        assertThat(payload).isEqualTo(new ChatMessagePayload.Encrypted(ciphertext, iv, 1));
    }

    @Test
    void malformedCiphertextIsRejected() {
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, bytes(40), bytes(16), 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, bytes(40), bytes(12), 2, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, bytes(16), bytes(12), 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, "not base64!", bytes(12), 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, "A".repeat(20_000), bytes(12), 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(required, SendChatMessageRequest.encrypted(conversationId, bytes(40), null, 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
    }

    @Test
    void phaseOneAcceptsEitherShapeButNeverBoth() {
        assertThat(available.validate(new SendChatMessageRequest(conversationId, "  Oi  ", null)))
                .isEqualTo(new ChatMessagePayload.Plaintext("Oi"));
        assertThat(available.validate(SendChatMessageRequest.encrypted(conversationId, bytes(40), bytes(12), 1, null)))
                .isInstanceOf(ChatMessagePayload.Encrypted.class);

        assertRejected(available, new SendChatMessageRequest(conversationId, "Oi", bytes(40), bytes(12), 1, clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(available, new SendChatMessageRequest(conversationId, "   ", clientMessageId),
                E2eeError.INVALID_PAYLOAD);
        assertRejected(available, new SendChatMessageRequest(conversationId, "x".repeat(4001), clientMessageId),
                E2eeError.INVALID_PAYLOAD);
    }

    @Test
    void disabledModeKeepsLegacyChatAndRefusesCiphertext() {
        assertThat(disabled.validate(new SendChatMessageRequest(conversationId, "Oi", null)))
                .isEqualTo(new ChatMessagePayload.Plaintext("Oi"));

        assertRejected(disabled, SendChatMessageRequest.encrypted(conversationId, bytes(40), bytes(12), 1, clientMessageId),
                E2eeError.UNAVAILABLE);
    }

    @Test
    void requiredWithoutAvailableDoesNotStart() {
        assertThatThrownBy(() -> new E2eeProperties(false, true))
                .isInstanceOf(IllegalStateException.class);
    }

    private void assertRejected(ChatMessagePayloadPolicy policy, SendChatMessageRequest request, E2eeError error) {
        assertThatThrownBy(() -> policy.validate(request))
                .isInstanceOfSatisfying(E2eeException.class, exception -> {
                    assertThat(exception.getError()).isEqualTo(error);
                    assertThat(exception.getConversationId()).isEqualTo(conversationId);
                    assertThat(exception.getClientMessageId()).isEqualTo(request.clientMessageId());
                    // O erro nunca ecoa o conteúdo recebido.
                    assertThat(exception.getBody().getDetail()).isEqualTo(error.detail());
                });
    }

    private static String bytes(int length) {
        byte[] value = new byte[length];
        RANDOM.nextBytes(value);
        return Base64.getEncoder().encodeToString(value);
    }
}
