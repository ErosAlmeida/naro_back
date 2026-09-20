package com.naro.presence.application.service;

import com.naro.presence.application.dto.PresenceResponse;
import com.naro.presence.application.event.PresenceChangedEvent;
import com.naro.presence.domain.enums.PresenceStatus;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserLastSeen;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PresenceServiceImpl implements PresenceService {

    private final PresenceStore presenceStore;

    private final PresenceVisibilityPolicy visibilityPolicy;

    private final UserRepository userRepository;

    private final ApplicationEventPublisher eventPublisher;


    public PresenceServiceImpl(
            PresenceStore presenceStore,
            PresenceVisibilityPolicy visibilityPolicy,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.presenceStore = presenceStore;
        this.visibilityPolicy = visibilityPolicy;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }


    /*
     * Só a primeira sessão gera evento.
     * Abrir a segunda aba não notifica ninguém.
     */
    @Override
    @Transactional(readOnly = true)
    public void connected(
            UUID userId,
            String sessionId
    ) {

        if (!presenceStore.addSession(userId, sessionId)) {
            return;
        }


        publish(
                new PresenceResponse(
                        userId,
                        PresenceStatus.ONLINE,
                        null
                )
        );
    }


    /*
     * Só a última sessão deixa o usuário OFFLINE.
     * É o único momento em que last_seen_at é gravado.
     */
    @Override
    @Transactional
    public void disconnected(
            UUID userId,
            String sessionId
    ) {

        if (!presenceStore.removeSession(userId, sessionId)) {
            return;
        }


        Instant lastSeenAt = Instant.now();

        userRepository.updateLastSeenAt(
                userId,
                lastSeenAt
        );


        /*
         * Reconectou enquanto gravávamos (ex.: reload da página):
         * o ONLINE da nova sessão já foi enviado.
         */
        if (presenceStore.isOnline(userId)) {
            return;
        }


        publish(
                new PresenceResponse(
                        userId,
                        PresenceStatus.OFFLINE,
                        lastSeenAt
                )
        );
    }


    @Override
    @Transactional(readOnly = true)
    public List<PresenceResponse> getPresence(
            Collection<UUID> userIds,
            User viewer
    ) {

        Set<UUID> visible =
                visibilityPolicy.visibleTo(
                        viewer.getId(),
                        userIds
                );


        if (visible.isEmpty()) {
            return List.of();
        }


        Set<UUID> online =
                presenceStore.onlineAmong(visible);


        Map<UUID, Instant> lastSeenByUser = new HashMap<>();

        for (UserLastSeen lastSeen : userRepository.findLastSeenByIds(visible)) {
            lastSeenByUser.put(lastSeen.userId(), lastSeen.lastSeenAt());
        }


        return visible
                .stream()
                .map(userId ->
                        online.contains(userId)
                                ? new PresenceResponse(userId, PresenceStatus.ONLINE, null)
                                : new PresenceResponse(userId, PresenceStatus.OFFLINE, lastSeenByUser.get(userId))
                )
                .toList();
    }


    private void publish(
            PresenceResponse presence
    ) {

        Set<String> audience =
                visibilityPolicy.audienceOf(
                        presence.userId()
                );


        if (audience.isEmpty()) {
            return;
        }


        eventPublisher.publishEvent(
                new PresenceChangedEvent(
                        presence,
                        audience
                )
        );
    }
}
