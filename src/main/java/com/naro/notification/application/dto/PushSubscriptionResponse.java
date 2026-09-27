package com.naro.notification.application.dto;

import com.naro.notification.domain.entity.PushSubscription;

import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.UUID;

/**
 * Um aparelho com notificações. Só o dono recebe, e nunca o endpoint nem
 * as chaves: eles só servem ao servidor para entregar o push.
 */
@Relation(collectionRelation = "pushSubscriptions")
public record PushSubscriptionResponse(

        UUID id,

        String deviceName,

        String userAgent,

        boolean enabled,

        Instant createdAt,

        Instant updatedAt,

        Instant lastUsedAt

) {

    public static PushSubscriptionResponse of(PushSubscription subscription) {
        return new PushSubscriptionResponse(
                subscription.getId(),
                subscription.getDeviceName(),
                subscription.getUserAgent(),
                subscription.isEnabled(),
                subscription.getCreatedAt(),
                subscription.getUpdatedAt(),
                subscription.getLastUsedAt()
        );
    }
}
