package com.naro.chat.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatErrorResponse(

        String code,

        String message,

        Instant timestamp,

        UUID conversationId,

        UUID clientMessageId

) {
    public ChatErrorResponse(String code, String message, Instant timestamp) {
        this(code, message, timestamp, null, null);
    }
}
