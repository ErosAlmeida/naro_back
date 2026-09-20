package com.naro.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

public record LikeResponse(

        UUID userId,

        String username,

        Instant createdAt

) {
}
