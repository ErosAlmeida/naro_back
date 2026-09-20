package com.naro.realtime.application.dto;

import java.time.Instant;

public record WebSocketTicketResponse(

        String ticket,

        Instant expiresAt

) {
}
