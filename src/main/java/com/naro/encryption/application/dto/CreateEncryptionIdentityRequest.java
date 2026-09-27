package com.naro.encryption.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Primeira ativação: identidade e primeira passkey numa única transação,
 * para nunca existir identidade sem nenhuma passkey capaz de abri-la.
 */
public record CreateEncryptionIdentityRequest(

        /* Ponto P-256 não comprimido (65 bytes), em Base64. */
        @NotBlank
        @Size(max = 128)
        String publicKey,

        /* PKCS#8 cifrado com a Master Key (AES-256-GCM), em Base64. */
        @NotBlank
        @Size(max = 1024)
        String wrappedPrivateKey,

        @NotBlank
        @Size(max = 32)
        String privateKeyIv,

        @NotBlank
        @Size(max = 30)
        String algorithm,

        @NotNull
        Integer version,

        @NotBlank
        @Size(max = 64)
        String masterKeyProof,

        @NotNull
        @Valid
        WrappedMasterKeyRequest key

) {
}
