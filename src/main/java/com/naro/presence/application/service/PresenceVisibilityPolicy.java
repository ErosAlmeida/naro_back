package com.naro.presence.application.service;

import com.naro.chat.domain.repository.ConversationRepository;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Único ponto que decide quem pode ver a presença
 * (online / visto por último) de quem.
 *
 * Regra atual: você vê a presença de quem compartilha
 * ao menos uma conversa privada com você.
 *
 * Quando existirem amigos e configurações de privacidade
 * ("todos", "amigos", "ninguém"), a regra muda aqui,
 * sem tocar em WebSocket, controllers ou no store.
 */
@Component
public class PresenceVisibilityPolicy {

    private final ConversationRepository conversationRepository;


    public PresenceVisibilityPolicy(
            ConversationRepository conversationRepository
    ) {
        this.conversationRepository = conversationRepository;
    }


    /**
     * Dentre targetIds, quais o viewer pode ver.
     * O próprio viewer sempre pode ver a si mesmo.
     */
    public Set<UUID> visibleTo(
            UUID viewerId,
            Collection<UUID> targetIds
    ) {

        if (targetIds.isEmpty()) {
            return Set.of();
        }


        Set<UUID> visible =
                new HashSet<>(
                        conversationRepository.findContactIdsAmong(
                                viewerId,
                                targetIds
                        )
                );


        if (targetIds.contains(viewerId)) {
            visible.add(viewerId);
        }


        return visible;
    }


    /**
     * keycloakIds de quem deve ser notificado quando
     * a presença de userId muda.
     */
    public Set<String> audienceOf(
            UUID userId
    ) {
        return conversationRepository.findContactKeycloakIds(userId);
    }
}
