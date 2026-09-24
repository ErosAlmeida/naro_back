package com.naro.presence.presentation.controller;

import com.naro.config.CurrentUser;
import com.naro.exception.BadRequestException;
import com.naro.presence.application.dto.PresenceResponse;
import com.naro.presence.application.service.PresenceService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Presence", description = "Quem está online; as mudanças chegam pelo WebSocket")
@RestController
@RequestMapping("/api/v1/presence")
public class PresenceRestController {

    static final int MAX_USERS_PER_REQUEST = 100;


    private final PresenceService presenceService;


    public PresenceRestController(
            PresenceService presenceService
    ) {
        this.presenceService = presenceService;
    }


    /*
     * Estado inicial. As mudanças seguintes chegam
     * por /user/queue/presence.
     */
    @GetMapping
    @Operation(summary = "Presença atual de até 100 usuários")
    public List<PresenceResponse> getPresence(

            @RequestParam(
                    defaultValue = ""
            )
            Set<UUID> userIds,

            @CurrentUser
            User currentUser

    ) {

        if (userIds.size() > MAX_USERS_PER_REQUEST) {

            throw new BadRequestException(
                    "At most "
                            + MAX_USERS_PER_REQUEST
                            + " users per request"
            );
        }


        return presenceService.getPresence(
                userIds,
                currentUser
        );
    }
}
