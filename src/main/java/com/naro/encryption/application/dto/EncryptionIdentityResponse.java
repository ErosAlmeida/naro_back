package com.naro.encryption.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Identidade do próprio usuário, com a chave privada cifrada que
 * só a Master Key abre. Nunca é devolvida para outra pessoa.
 */
public record EncryptionIdentityResponse(

        UUID userId,

        String publicKey,

        String wrappedPrivateKey,

        String privateKeyIv,

        String algorithm,

        int version,

        Instant createdAt

) {
}
