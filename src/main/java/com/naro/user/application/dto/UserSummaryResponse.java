package com.naro.user.application.dto;

import java.util.UUID;

public record UserSummaryResponse(

        UUID id,

        String username,

        String firstName,

        String lastName,

        String avatarUrl

) {
}
