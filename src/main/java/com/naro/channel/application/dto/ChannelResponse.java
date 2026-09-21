package com.naro.channel.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * As imagens vêm como URLs de leitura pré-assinadas (e expiram), no mesmo
 * formato de PublicationResponse#imageUrl. As chaves ficam no banco.
 */
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
