package com.naro.encryption.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/**
 * Master Key cifrada para uma passkey. Só o próprio dono recebe.
 */
@Relation(collectionRelation = "encryptionKeys")
public record EncryptionKeyResponse(

        String credentialId,

        String wrappedMasterKey,

        String iv,

        String algorithm,

        int version,

        Instant createdAt,

        Instant updatedAt

) {
}
