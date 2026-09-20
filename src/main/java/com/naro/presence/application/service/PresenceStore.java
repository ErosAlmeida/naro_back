package com.naro.presence.application.service;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Conexões WebSocket ativas por usuário.
 *
 * Um usuário pode ter várias sessões (abas, celular, desktop).
 * Ele só está OFFLINE quando não resta nenhuma.
 *
 * A implementação atual é em memória (uma instância).
 * Para várias instâncias, basta outra implementação
 * (ex.: Redis com um SET de sessões por usuário).
 */
public interface PresenceStore {

    /**
     * @return true se esta foi a primeira sessão do usuário
     *         (transição OFFLINE → ONLINE).
     */
    boolean addSession(
            UUID userId,
            String sessionId
    );


    /**
     * Idempotente: remover uma sessão desconhecida não tem efeito.
     *
     * @return true se esta era a última sessão do usuário
     *         (transição ONLINE → OFFLINE).
     */
    boolean removeSession(
            UUID userId,
            String sessionId
    );


    boolean isOnline(
            UUID userId
    );


    Set<UUID> onlineAmong(
            Collection<UUID> userIds
    );
}
