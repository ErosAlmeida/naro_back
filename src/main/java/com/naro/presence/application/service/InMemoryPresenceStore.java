package com.naro.presence.application.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
public class InMemoryPresenceStore implements PresenceStore {

    /*
     * Invariante: um userId só existe no mapa enquanto tiver
     * ao menos uma sessão. Assim containsKey() responde
     * "está online?" sem ler os Sets internos fora do compute().
     */
    private final ConcurrentMap<UUID, Set<String>> sessionsByUser =
            new ConcurrentHashMap<>();


    @Override
    public boolean addSession(
            UUID userId,
            String sessionId
    ) {

        AtomicBoolean firstSession = new AtomicBoolean(false);


        sessionsByUser.compute(userId, (id, sessions) -> {

            Set<String> current = sessions;

            if (current == null) {
                current = new HashSet<>();
                firstSession.set(true);
            }

            current.add(sessionId);

            return current;
        });


        return firstSession.get();
    }


    @Override
    public boolean removeSession(
            UUID userId,
            String sessionId
    ) {

        AtomicBoolean lastSession = new AtomicBoolean(false);


        sessionsByUser.computeIfPresent(userId, (id, sessions) -> {

            if (!sessions.remove(sessionId)) {
                return sessions;
            }

            if (sessions.isEmpty()) {
                lastSession.set(true);
                return null;
            }

            return sessions;
        });


        return lastSession.get();
    }


    @Override
    public boolean isOnline(
            UUID userId
    ) {
        return sessionsByUser.containsKey(userId);
    }


    @Override
    public Set<UUID> onlineAmong(
            Collection<UUID> userIds
    ) {

        return userIds
                .stream()
                .filter(sessionsByUser::containsKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
