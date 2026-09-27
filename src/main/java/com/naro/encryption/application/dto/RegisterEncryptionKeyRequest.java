package com.naro.encryption.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cadastra mais uma passkey para a Master Key existente.
 *
 * Não há userId: o dono vem do JWT. masterKeyProof prova que quem
 * cadastra já desbloqueou a Master Key; uma sessão roubada sozinha
 * não consegue cadastrar passkeys nem revogar as existentes.
 */
public record RegisterEncryptionKeyRequest(

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
        Integer version,

        @NotBlank
        @Size(max = 64)
        String masterKeyProof

) {
    public WrappedMasterKeyRequest toWrappedMasterKey() {
        return new WrappedMasterKeyRequest(credentialId, wrappedMasterKey, iv, algorithm, version);
    }
}
