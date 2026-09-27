package com.naro.notification.application.service;

import com.naro.notification.application.dto.PushNotificationPayload;
import com.naro.notification.config.PushProperties;
import com.naro.notification.domain.entity.PushSubscription;
import com.naro.notification.domain.repository.PushSubscriptionRepository;
import com.naro.notification.webpush.PushDeliveryResult;
import com.naro.notification.webpush.PushEndpointPolicy;
import com.naro.notification.webpush.WebPushClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Mock
    private PushSubscriptionRepository repository;

    @Mock
    private WebPushClient webPushClient;

    private PushNotificationService service;

    private final UUID userId = UUID.randomUUID();

    private final PushNotificationPayload payload = new PushNotificationPayload(
            PushNotificationPayload.CHAT_MESSAGE, UUID.randomUUID(), UUID.randomUUID(),
            new PushNotificationPayload.Sender("Leonardo", null), "Você vai entrar hoje?",
            "/chat/x", Instant.parse("2026-09-27T12:00:00Z"), 2);

    @BeforeEach
    void setUp() {
        PushProperties properties = new PushProperties(
                new PushProperties.Vapid(null, null, null), PushProperties.PreviewMode.NAME_AND_PREVIEW,
                List.of("fcm.googleapis.com", "web.push.apple.com"), Duration.ofHours(24), 10);

        service = new PushNotificationService(repository, webPushClient,
                new PushEndpointPolicy(properties.allowedEndpointHosts()), properties, JSON);

        lenient().when(webPushClient.isEnabled()).thenReturn(true);
    }

    @Test
    void sendsTheSamePayloadToEveryDeviceOfTheUser() {
        PushSubscription phone = subscription("https://fcm.googleapis.com/fcm/send/phone");
        PushSubscription laptop = subscription("https://web.push.apple.com/laptop");
        when(repository.findAllByUser_IdAndEnabledTrue(userId)).thenReturn(List.of(phone, laptop));
        when(webPushClient.send(any(), any(), any(), any(), any())).thenReturn(PushDeliveryResult.DELIVERED);

        assertThat(service.send(userId, payload)).isEqualTo(2);

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(webPushClient).send(eq(phone.getEndpoint()), eq(phone.getP256dh()), eq(phone.getAuth()), body.capture(), eq(Duration.ofHours(24)));
        verify(webPushClient).send(eq(laptop.getEndpoint()), any(), any(), any(), any());

        JsonNode json = JSON.readTree(body.getValue());
        assertThat(json.get("type").asString()).isEqualTo("CHAT_MESSAGE");
        assertThat(json.get("preview").asString()).isEqualTo("Você vai entrar hoje?");
        assertThat(json.get("sender").get("displayName").asString()).isEqualTo("Leonardo");
        assertThat(json.get("unreadConversations").asLong()).isEqualTo(2);
        assertThat(json.get("createdAt").asString()).isEqualTo("2026-09-27T12:00:00Z");
        // Nada da conta além do necessário para abrir a conversa.
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "type", "conversationId", "messageId", "sender", "preview", "url", "createdAt", "unreadConversations");

        ArgumentCaptor<Collection<UUID>> touched = ArgumentCaptor.captor();
        verify(repository).touchLastUsed(touched.capture(), any(), any());
        assertThat(touched.getValue()).containsExactlyInAnyOrder(phone.getId(), laptop.getId());
    }

    @Test
    void expiredSubscriptionsAreRemovedAndRejectedOnesDisabled() {
        PushSubscription expired = subscription("https://fcm.googleapis.com/fcm/send/expired");
        PushSubscription rejected = subscription("https://web.push.apple.com/rejected");
        PushSubscription flaky = subscription("https://fcm.googleapis.com/fcm/send/flaky");
        PushSubscription working = subscription("https://fcm.googleapis.com/fcm/send/working");
        when(repository.findAllByUser_IdAndEnabledTrue(userId)).thenReturn(List.of(expired, rejected, flaky, working));
        when(webPushClient.send(eq(expired.getEndpoint()), any(), any(), any(), any())).thenReturn(PushDeliveryResult.EXPIRED);
        when(webPushClient.send(eq(rejected.getEndpoint()), any(), any(), any(), any())).thenReturn(PushDeliveryResult.REJECTED);
        when(webPushClient.send(eq(flaky.getEndpoint()), any(), any(), any(), any())).thenReturn(PushDeliveryResult.FAILED);
        when(webPushClient.send(eq(working.getEndpoint()), any(), any(), any(), any())).thenReturn(PushDeliveryResult.DELIVERED);

        assertThat(service.send(userId, payload)).isEqualTo(1);

        verify(repository).deleteIfUnchanged(expired.getId(), expired.getUpdatedAt());
        verify(repository).disableIfUnchanged(rejected.getId(), rejected.getUpdatedAt());
        verify(repository, never()).deleteIfUnchanged(eq(flaky.getId()), any());
        verify(repository, never()).disableIfUnchanged(eq(flaky.getId()), any());
        verify(repository).touchLastUsed(eq(List.of(working.getId())), any(), any());
    }

    @Test
    void anExceptionFromOneDeviceDoesNotStopTheOthers() {
        PushSubscription broken = subscription("https://fcm.googleapis.com/fcm/send/broken");
        PushSubscription working = subscription("https://web.push.apple.com/working");
        when(repository.findAllByUser_IdAndEnabledTrue(userId)).thenReturn(List.of(broken, working));
        when(webPushClient.send(eq(broken.getEndpoint()), any(), any(), any(), any())).thenThrow(new IllegalArgumentException("bad key"));
        when(webPushClient.send(eq(working.getEndpoint()), any(), any(), any(), any())).thenReturn(PushDeliveryResult.DELIVERED);

        assertThat(service.send(userId, payload)).isEqualTo(1);

        verify(repository, never()).deleteIfUnchanged(eq(broken.getId()), any());
    }

    @Test
    void anEndpointNoLongerAllowedIsDisabledWithoutBeingCalled() {
        PushSubscription removedService = subscription("https://updates.push.services.mozilla.com/wpush/v2/x");
        when(repository.findAllByUser_IdAndEnabledTrue(userId)).thenReturn(List.of(removedService));

        assertThat(service.send(userId, payload)).isZero();

        verify(webPushClient, never()).send(any(), any(), any(), any(), any());
        verify(repository).disableIfUnchanged(removedService.getId(), removedService.getUpdatedAt());
        verify(repository, never()).touchLastUsed(anyCollection(), any(), any());
    }

    @Test
    void aUserWithoutDevicesCostsOneQuery() {
        when(repository.findAllByUser_IdAndEnabledTrue(userId)).thenReturn(List.of());

        assertThat(service.send(userId, payload)).isZero();

        verify(webPushClient, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void doesNothingWithoutWebPushConfigured() {
        when(webPushClient.isEnabled()).thenReturn(false);

        assertThat(service.send(userId, payload)).isZero();

        verifyNoInteractions(repository);
    }

    private PushSubscription subscription(String endpoint) {
        PushSubscription subscription = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(subscription, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(subscription, "endpoint", endpoint);
        ReflectionTestUtils.setField(subscription, "p256dh", "p256dh-" + endpoint.hashCode());
        ReflectionTestUtils.setField(subscription, "auth", "auth-" + endpoint.hashCode());
        ReflectionTestUtils.setField(subscription, "enabled", true);
        ReflectionTestUtils.setField(subscription, "updatedAt", Instant.now());
        return subscription;
    }
}
