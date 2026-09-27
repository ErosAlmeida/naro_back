package com.naro.notification.application.service;

import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.NotFoundException;
import com.naro.notification.application.dto.PushSubscriptionResponse;
import com.naro.notification.application.dto.RegisterPushSubscriptionRequest;
import com.naro.notification.application.dto.VapidPublicKeyResponse;
import com.naro.notification.config.PushProperties;
import com.naro.notification.domain.entity.PushSubscription;
import com.naro.notification.domain.repository.PushSubscriptionRepository;
import com.naro.notification.webpush.EcKeys;
import com.naro.notification.webpush.PushEndpointPolicy;
import com.naro.notification.webpush.WebPushClient;
import com.naro.user.domain.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class PushSubscriptionServiceImpl
        implements PushSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(PushSubscriptionServiceImpl.class);

    static final int AUTH_SECRET_BYTES = 16;

    static final int MAX_DEVICE_NAME_LENGTH = 100;

    static final int MAX_USER_AGENT_LENGTH = 512;

    private final PushSubscriptionRepository repository;

    private final PushEndpointPolicy endpointPolicy;

    private final WebPushClient webPushClient;

    private final PushProperties properties;


    public PushSubscriptionServiceImpl(
            PushSubscriptionRepository repository,
            PushEndpointPolicy endpointPolicy,
            WebPushClient webPushClient,
            PushProperties properties
    ) {
        this.repository = repository;
        this.endpointPolicy = endpointPolicy;
        this.webPushClient = webPushClient;
        this.properties = properties;
    }


    @Override
    public VapidPublicKeyResponse getVapidPublicKey() {
        return webPushClient.publicKey()
                .map(key -> new VapidPublicKeyResponse(true, key))
                .orElseGet(() -> new VapidPublicKeyResponse(false, null));
    }


    @Override
    @Transactional
    public Registration register(
            RegisterPushSubscriptionRequest request,
            String userAgent,
            User currentUser
    ) {

        if (!webPushClient.isEnabled()) {
            throw new ConflictException("Push notifications are not available");
        }

        String endpoint = request.endpoint().strip();

        // O servidor faz POST neste endereço: só push services conhecidos (SSRF).
        if (!endpointPolicy.isAllowed(endpoint)) {
            throw new BadRequestException("Unsupported push service");
        }

        String p256dh = canonicalPublicKey(request.keys().p256dh());
        String auth = canonicalAuthSecret(request.keys().auth());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        repository.upsert(
                UUID.randomUUID(),
                currentUser.getId(),
                endpoint,
                p256dh,
                auth,
                displayText(request.deviceName(), MAX_DEVICE_NAME_LENGTH),
                displayText(userAgent, MAX_USER_AGENT_LENGTH),
                now
        );

        PushSubscription subscription = repository
                .findByEndpoint(endpoint)
                .orElseThrow(() -> new IllegalStateException("Push subscription upsert not visible"));

        repository.deleteBeyondLimit(
                currentUser.getId(),
                Math.max(1, properties.maxSubscriptionsPerUser())
        );

        boolean created = subscription.getCreatedAt().equals(now);

        if (created) {
            log.info("Push subscription {} registered for user {} ({})",
                    subscription.getId(), currentUser.getId(), URI.create(endpoint).getHost());
        }

        return new Registration(PushSubscriptionResponse.of(subscription), created);
    }


    @Override
    @Transactional(readOnly = true)
    public List<PushSubscriptionResponse> getSubscriptions(
            User currentUser
    ) {
        return repository
                .findAllByUser_IdOrderByCreatedAtDesc(currentUser.getId())
                .stream()
                .map(PushSubscriptionResponse::of)
                .toList();
    }


    @Override
    @Transactional
    public void delete(
            UUID subscriptionId,
            User currentUser
    ) {
        if (repository.deleteOwned(subscriptionId, currentUser.getId()) == 0) {
            throw new NotFoundException("Push subscription not found");
        }
    }


    /* Ponto P-256 válido (na curva); guardado sempre no mesmo Base64url sem padding. */
    private static String canonicalPublicKey(String value) {
        try {
            return EcKeys.encodeBase64Url(EcKeys.encode(EcKeys.publicKey(EcKeys.decodeBase64Url(value))));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid push subscription keys");
        }
    }


    private static String canonicalAuthSecret(String value) {
        try {
            byte[] secret = EcKeys.decodeBase64Url(value);
            if (secret.length == AUTH_SECRET_BYTES) {
                return EcKeys.encodeBase64Url(secret);
            }
        } catch (IllegalArgumentException _) {
            // cai no erro abaixo
        }
        throw new BadRequestException("Invalid push subscription keys");
    }


    /* Texto só para exibição: sem caracteres de controle, espaços colapsados, limitado. */
    static String displayText(String value, int maxLength) {
        if (value == null) {
            return null;
        }

        String cleaned = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();

        if (cleaned.isEmpty()) {
            return null;
        }

        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength).strip();
    }
}
