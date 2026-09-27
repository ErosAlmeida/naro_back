package com.naro.notification.application.service;

import com.naro.notification.application.dto.PushNotificationPayload;
import com.naro.notification.config.PushProperties;
import com.naro.notification.domain.entity.PushSubscription;
import com.naro.notification.domain.repository.PushSubscriptionRepository;
import com.naro.notification.webpush.PushDeliveryResult;
import com.naro.notification.webpush.PushEndpointPolicy;
import com.naro.notification.webpush.WebPushClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entrega um push a todos os aparelhos ativos de um usuário e mantém a
 * lista limpa: inscrição expirada (404/410) é apagada; recusada pelo push
 * service (400/401/403) é desativada até o navegador se cadastrar de novo.
 *
 * Roda na thread do push (PushConfiguration.PUSH_EXECUTOR), nunca na do chat.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    static final Duration LAST_USED_RESOLUTION = Duration.ofHours(1);

    private final PushSubscriptionRepository repository;

    private final WebPushClient webPushClient;

    private final PushEndpointPolicy endpointPolicy;

    private final PushProperties properties;

    private final JsonMapper jsonMapper;


    public PushNotificationService(
            PushSubscriptionRepository repository,
            WebPushClient webPushClient,
            PushEndpointPolicy endpointPolicy,
            PushProperties properties,
            JsonMapper jsonMapper
    ) {
        this.repository = repository;
        this.webPushClient = webPushClient;
        this.endpointPolicy = endpointPolicy;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }


    public boolean isEnabled() {
        return webPushClient.isEnabled();
    }


    /** @return quantos aparelhos o push service aceitou */
    public int send(UUID userId, PushNotificationPayload payload) {

        if (!webPushClient.isEnabled()) {
            return 0;
        }

        List<PushSubscription> subscriptions = repository.findAllByUser_IdAndEnabledTrue(userId);

        if (subscriptions.isEmpty()) {
            return 0;
        }

        byte[] body = jsonMapper.writeValueAsBytes(payload);
        List<UUID> delivered = new ArrayList<>();

        for (PushSubscription subscription : subscriptions) {
            switch (deliver(subscription, body)) {
                case DELIVERED -> delivered.add(subscription.getId());
                case EXPIRED -> {
                    repository.deleteIfUnchanged(subscription.getId(), subscription.getUpdatedAt());
                    log.info("Push subscription {} expired and was removed", subscription.getId());
                }
                case REJECTED -> {
                    repository.disableIfUnchanged(subscription.getId(), subscription.getUpdatedAt());
                    log.info("Push subscription {} was rejected and disabled", subscription.getId());
                }
                case FAILED -> {
                    // Nada a mudar: rede ou push service instável.
                }
            }
        }

        if (!delivered.isEmpty()) {
            Instant now = Instant.now();
            repository.touchLastUsed(delivered, now, now.minus(LAST_USED_RESOLUTION));
        }

        return delivered.size();
    }


    private PushDeliveryResult deliver(PushSubscription subscription, byte[] body) {

        // A lista de push services pode ter mudado depois do cadastro.
        if (!endpointPolicy.isAllowed(subscription.getEndpoint())) {
            return PushDeliveryResult.REJECTED;
        }

        try {
            return webPushClient.send(
                    subscription.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    body,
                    properties.ttl()
            );
        } catch (RuntimeException exception) {
            // Só a classe: a mensagem poderia citar o endpoint.
            log.warn("Push to subscription {} failed ({})", subscription.getId(), exception.getClass().getSimpleName());
            return PushDeliveryResult.FAILED;
        }
    }
}
