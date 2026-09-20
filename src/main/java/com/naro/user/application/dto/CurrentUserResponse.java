package com.naro.user.application.dto;

import java.util.UUID;

/**
 * id é o UUID interno do Naro, o mesmo usado em
 * authorId, senderId, userId etc. O keycloakId (sub)
 * não é exposto.
 */
public record CurrentUserResponse(

        UUID id,

        String username,

        String email,

        String firstName,

        String lastName

) {
}
