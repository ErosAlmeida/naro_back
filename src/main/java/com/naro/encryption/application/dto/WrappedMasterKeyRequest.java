package com.naro.encryption.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A Master Key cifrada no browser com a KEK de uma passkey.
 * Nunca a Master Key, a KEK ou a saída do PRF.
 */
public record WrappedMasterKeyRequest(

        /* Base64URL sem padding, como o WebAuthn devolve. */
        @NotBlank
        @Size(max = 1400)
        String credentialId,

        @NotBlank
        @Size(max = 128)
        String wrappedMasterKey,

        @NotBlank
        @Size(max = 32)
        String iv,

        @NotBlank
        @Size(max = 30)
        String algorithm,

        @NotNull
        Integer version

) {
}
