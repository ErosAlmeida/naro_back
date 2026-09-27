package com.naro.chat.application.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Qual conversa cada sessão WebSocket está mostrando agora (aba visível e em
 * foco). É o que evita push de uma conversa que a pessoa já está vendo.
 *
 * Estado efêmero, em memória, como o PresenceStore: cada sessão ocupa no
 * máximo uma entrada, removida ao trocar de tela ou ao desconectar
 * (inclusive por falta de heartbeat). Para várias instâncias, a mesma troca
 * do PresenceStore vale aqui (ex.: Redis).
 *
 * Os Maps internos só são lidos e alterados dentro de compute(), como no
 * InMemoryPresenceStore.
 */
@Component
public class ConversationFocusRegistry {

    /* userId -> (sessionId -> conversationId) */
    private final ConcurrentMap<UUID, Map<String, UUID>> focusByUser = new ConcurrentHashMap<>();


    public void focus(UUID userId, String sessionId, UUID conversationId) {
        focusByUser.compute(userId, (_, sessions) -> {
            Map<String, UUID> current = sessions == null ? new HashMap<>() : sessions;
            current.put(sessionId, conversationId);
            return current;
        });
    }


    /** Idempotente: limpar uma sessão sem foco não tem efeito. */
    public void clear(UUID userId, String sessionId) {
        focusByUser.computeIfPresent(userId, (_, sessions) -> {
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }


    /** Alguma sessão do usuário (qualquer aparelho) está com a conversa na tela. */
    public boolean isViewing(UUID userId, UUID conversationId) {
        AtomicBoolean viewing = new AtomicBoolean(false);

        focusByUser.computeIfPresent(userId, (_, sessions) -> {
            viewing.set(sessions.containsValue(conversationId));
            return sessions;
        });

        return viewing.get();
    }
}
