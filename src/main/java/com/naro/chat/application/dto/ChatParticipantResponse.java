package com.naro.chat.application.dto;

import java.util.UUID;

public record ChatParticipantResponse(

        UUID userId,

        String username

) {
}