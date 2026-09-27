package com.naro.encryption.application.service;

import com.naro.encryption.application.dto.CreateEncryptionIdentityRequest;
import com.naro.encryption.application.dto.E2eeStatusResponse;
import com.naro.encryption.application.dto.EncryptionIdentityResponse;
import com.naro.encryption.application.dto.EncryptionKeyResponse;
import com.naro.encryption.application.dto.PublicIdentityResponse;
import com.naro.encryption.application.dto.RegisterEncryptionKeyRequest;
import com.naro.user.domain.entity.User;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Guarda e entrega material criptográfico já cifrado no browser.
 * Nenhum método recebe ou devolve Master Key, KEK ou plaintext.
 */
public interface EncryptionKeyService {

    E2eeStatusResponse getStatus();

    EncryptionIdentityResponse getOwnIdentity(User currentUser);

    EncryptionIdentityResponse createIdentity(CreateEncryptionIdentityRequest request, User currentUser);

    /**
     * Chaves públicas de quem divide ao menos uma conversa com o usuário
     * (e a dele mesmo). Quem ainda não ativou a proteção não aparece.
     */
    List<PublicIdentityResponse> getContactIdentities(Collection<UUID> userIds, User currentUser);

    List<EncryptionKeyResponse> getKeys(User currentUser);

    EncryptionKeyResponse registerKey(RegisterEncryptionKeyRequest request, User currentUser);

    void revokeKey(String credentialId, String masterKeyProof, User currentUser);
}
