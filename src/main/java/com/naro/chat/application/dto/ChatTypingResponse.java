package com.naro.chat.application.dto;

import java.util.UUID;

public record ChatTypingResponse(

        UUID conversationId,

        UUID userId,

        String username,

        boolean typing

) {
}
