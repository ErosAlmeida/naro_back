package com.naro.publication.application.dto;

import java.time.Instant;
import java.util.UUID;

public record LikeResponse(

        UUID channelId,

        String slug,

        Instant createdAt

) {
}
