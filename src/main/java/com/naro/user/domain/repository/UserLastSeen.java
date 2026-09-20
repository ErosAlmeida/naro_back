package com.naro.user.domain.repository;

import java.time.Instant;
import java.util.UUID;

public record UserLastSeen(

        UUID userId,

        Instant lastSeenAt

) {
}
