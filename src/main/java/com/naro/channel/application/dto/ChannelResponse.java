package com.naro.channel.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

/**
 * As imagens vêm como URLs de leitura pré-assinadas (e expiram), no mesmo
 * formato de PublicationResponse#imageUrl. As chaves ficam no banco.
 */
@Relation(collectionRelation = "channels")
public record ChannelResponse(
        UUID id,
        UUID ownerId,
        String name,
        String slug,
        String description,
        String profileImageUrl,
        String bannerImageUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
