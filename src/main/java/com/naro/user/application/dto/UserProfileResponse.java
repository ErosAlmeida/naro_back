package com.naro.user.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Perfil público (canal). Não contém dados do mundo
 * privado: email, amigos, presença ou visto por último.
 */
public record UserProfileResponse(

        UUID id,

        String username,

        String firstName,

        String lastName,

        String bio,

        String avatarUrl,

        Instant createdAt

) {
}
