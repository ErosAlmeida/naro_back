package com.naro.presence.application.service;

import com.naro.presence.application.dto.PresenceResponse;
import com.naro.presence.application.event.PresenceChangedEvent;
import com.naro.presence.domain.enums.PresenceStatus;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserLastSeen;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Usa o InMemoryPresenceStore real: o comportamento com
 * várias sessões é o que interessa aqui.
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceImplTest {

    @Mock
    private PresenceVisibilityPolicy visibilityPolicy;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private PresenceServiceImpl presenceService;

    private final UUID leonardo = UUID.randomUUID();


    @BeforeEach
    void setUp() {
        presenceService = new PresenceServiceImpl(
                new InMemoryPresenceStore(),
                visibilityPolicy,
                userRepository,
                eventPublisher
        );
    }


    @Test
    void firstConnectionPublishesOnlineToContacts() {

        when(visibilityPolicy.audienceOf(leonardo)).thenReturn(Set.of("kc-maria"));

        presenceService.connected(leonardo, "chrome");

        PresenceChangedEvent event = publishedEvent();
        assertThat(event.presence().userId()).isEqualTo(leonardo);
        assertThat(event.presence().status()).isEqualTo(PresenceStatus.ONLINE);
        assertThat(event.presence().lastSeenAt()).isNull();
        assertThat(event.recipientKeycloakIds()).containsExactly("kc-maria");
    }


    @Test
    void secondConnectionDoesNotPublishAgain() {

        when(visibilityPolicy.audienceOf(leonardo)).thenReturn(Set.of("kc-maria"));

        presenceService.connected(leonardo, "chrome");
        presenceService.connected(leonardo, "celular");

        verify(eventPublisher).publishEvent(any(PresenceChangedEvent.class));
    }


    @Test
    void closingOneOfTwoConnectionsKeepsOnlineAndDoesNotTouchLastSeen() {

        when(visibilityPolicy.audienceOf(leonardo)).thenReturn(Set.of("kc-maria"));

        presenceService.connected(leonardo, "chrome");
        presenceService.connected(leonardo, "celular");
        presenceService.disconnected(leonardo, "chrome");

        verify(userRepository, never()).updateLastSeenAt(any(), any());
        verify(eventPublisher).publishEvent(any(PresenceChangedEvent.class));
    }


    @Test
    void closingLastConnectionStoresLastSeenAndPublishesOffline() {

        when(visibilityPolicy.audienceOf(leonardo)).thenReturn(Set.of("kc-maria"));

        presenceService.connected(leonardo, "chrome");
        Instant before = Instant.now();
        presenceService.disconnected(leonardo, "chrome");

        ArgumentCaptor<Instant> lastSeen = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).updateLastSeenAt(eq(leonardo), lastSeen.capture());
        assertThat(lastSeen.getValue()).isAfterOrEqualTo(before);

        ArgumentCaptor<PresenceChangedEvent> events =
                ArgumentCaptor.forClass(PresenceChangedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());

        PresenceResponse offline = events.getAllValues().get(1).presence();
        assertThat(offline.status()).isEqualTo(PresenceStatus.OFFLINE);
        assertThat(offline.lastSeenAt()).isEqualTo(lastSeen.getValue());
    }


    @Test
    void userWithoutContactsChangesPresenceWithoutPublishing() {

        when(visibilityPolicy.audienceOf(leonardo)).thenReturn(Set.of());

        presenceService.connected(leonardo, "chrome");
        presenceService.disconnected(leonardo, "chrome");

        verify(userRepository).updateLastSeenAt(eq(leonardo), any());
        verifyNoInteractions(eventPublisher);
    }


    @Test
    void disconnectOfUnknownSessionDoesNothing() {

        presenceService.disconnected(leonardo, "nunca-conectou");

        verifyNoInteractions(userRepository, eventPublisher);
    }


    @Test
    void getPresenceReturnsOnlyVisibleUsersWithLastSeenForOfflineOnes() {

        User viewer = user();
        UUID maria = UUID.randomUUID();
        UUID joao = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Instant joaoLastSeen = Instant.parse("2026-09-18T22:14:00Z");

        when(visibilityPolicy.audienceOf(maria)).thenReturn(Set.of());
        presenceService.connected(maria, "maria-chrome");

        List<UUID> requested = List.of(maria, joao, stranger);
        when(visibilityPolicy.visibleTo(viewer.getId(), requested)).thenReturn(Set.of(maria, joao));
        when(userRepository.findLastSeenByIds(Set.of(maria, joao)))
                .thenReturn(List.of(new UserLastSeen(maria, null), new UserLastSeen(joao, joaoLastSeen)));

        List<PresenceResponse> presence = presenceService.getPresence(requested, viewer);

        assertThat(presence).containsExactlyInAnyOrder(
                new PresenceResponse(maria, PresenceStatus.ONLINE, null),
                new PresenceResponse(joao, PresenceStatus.OFFLINE, joaoLastSeen)
        );
    }


    private PresenceChangedEvent publishedEvent() {

        ArgumentCaptor<PresenceChangedEvent> captor =
                ArgumentCaptor.forClass(PresenceChangedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        return captor.getValue();
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
