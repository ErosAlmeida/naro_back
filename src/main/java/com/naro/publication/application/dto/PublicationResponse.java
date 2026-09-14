package com.naro.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

public record PublicationResponse(

        UUID id,

        UUID authorId,

        String authorUsername,

        String content,

        String imageUrl,

        Instant createdAt

) {
}