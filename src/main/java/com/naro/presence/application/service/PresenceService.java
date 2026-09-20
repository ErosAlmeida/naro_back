package com.naro.presence.application.service;

import com.naro.presence.application.dto.PresenceResponse;
import com.naro.user.domain.entity.User;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PresenceService {

    void connected(
            UUID userId,
            String sessionId
    );


    void disconnected(
            UUID userId,
            String sessionId
    );


    /**
     * Presença dos usuários que o viewer pode ver.
     * Usuários fora da política de visibilidade são omitidos.
     */
    List<PresenceResponse> getPresence(
            Collection<UUID> userIds,
            User viewer
    );
}
