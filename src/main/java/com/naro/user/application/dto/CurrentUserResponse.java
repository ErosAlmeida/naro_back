package com.naro.user.application.dto;

public record CurrentUserResponse(
        String id,
        String username,
        String email,
        String firstName,
        String lastName
) {
}
