package com.naro.presence.application.event;

import com.naro.presence.application.dto.PresenceResponse;

import java.util.Set;

public record PresenceChangedEvent(

        PresenceResponse presence,

        Set<String> recipientKeycloakIds

) {
}
