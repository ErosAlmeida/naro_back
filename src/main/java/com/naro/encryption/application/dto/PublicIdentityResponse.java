package com.naro.encryption.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.util.UUID;

/**
 * Só a parte pública da identidade de um contato.
 */
@Relation(collectionRelation = "identities")
public record PublicIdentityResponse(

        UUID userId,

        String publicKey,

        String algorithm,

        int version

) {
}
