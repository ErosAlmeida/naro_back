package com.naro.notification.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * O mesmo formato de PushSubscription.toJSON() do navegador, mais um nome
 * amigável opcional do aparelho ("Chrome no Mac").
 *
 * Não há userId: o dono é sempre o usuário do JWT. expirationTime, se vier,
 * é ignorado (o push service avisa com 404/410 quando expira).
 */
public record RegisterPushSubscriptionRequest(

        @NotBlank
        @Size(max = 2048)
        String endpoint,

        @Valid
        @NotNull
        Keys keys,

        @Size(max = 100)
        String deviceName

) {

    public record Keys(

            @NotBlank
            @Size(max = 128)
            String p256dh,

            @NotBlank
            @Size(max = 64)
            String auth

    ) {
    }
}
