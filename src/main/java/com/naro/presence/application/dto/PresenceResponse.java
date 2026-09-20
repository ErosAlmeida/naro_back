package com.naro.presence.application.dto;

import com.naro.presence.domain.enums.PresenceStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * lastSeenAt só é preenchido quando OFFLINE
 * (e pode ser null se o usuário nunca se conectou).
 */
public record PresenceResponse(

        UUID userId,

        PresenceStatus status,

        Instant lastSeenAt

) {
}
