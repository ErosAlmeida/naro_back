package com.naro.chat.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ChatMessageResponse(

        UUID id,

        UUID conversationId,

        UUID senderId,

        String senderUsername,

        String content,

        Instant createdAt

) {
}