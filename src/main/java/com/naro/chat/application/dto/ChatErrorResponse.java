package com.naro.chat.application.dto;

import java.time.Instant;

public record ChatErrorResponse(

        String code,

        String message,

        Instant timestamp

) {
}