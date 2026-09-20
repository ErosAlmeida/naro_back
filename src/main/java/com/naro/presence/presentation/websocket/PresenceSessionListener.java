package com.naro.presence.presentation.websocket;

import com.naro.presence.application.service.PresenceService;
import com.naro.security.RealtimePrincipal;

import org.springframework.context.event.EventListener;

import org.springframework.messaging.simp.SimpMessageHeaderAccessor;

import org.springframework.security.core.Authentication;

import org.springframework.stereotype.Component;

import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Optional;

/**
 * Traduz o ciclo de vida das sessões STOMP em presença.
 *
 * SessionDisconnectEvent também é disparado quando o broker
 * encerra uma sessão sem heartbeat (navegador morto, rede caiu),
 * então conexões "fantasmas" não ficam online para sempre.
 */
@Component
public class PresenceSessionListener {

    private final PresenceService presenceService;


    public PresenceSessionListener(
            PresenceService presenceService
    ) {
        this.presenceService = presenceService;
    }


    @EventListener
    public void onConnected(
            SessionConnectedEvent event
    ) {

        String sessionId =
                SimpMessageHeaderAccessor.getSessionId(
                        event.getMessage().getHeaders()
                );


        realtimePrincipal(event.getUser())
                .ifPresent(principal ->
                        presenceService.connected(
                                principal.userId(),
                                sessionId
                        )
                );
    }


    /*
     * Pode ser disparado mais de uma vez para a mesma sessão;
     * PresenceStore#removeSession é idempotente.
     */
    @EventListener
    public void onDisconnected(
            SessionDisconnectEvent event
    ) {

        realtimePrincipal(event.getUser())
                .ifPresent(principal ->
                        presenceService.disconnected(
                                principal.userId(),
                                event.getSessionId()
                        )
                );
    }


    static Optional<RealtimePrincipal> realtimePrincipal(
            Principal user
    ) {

        if (
                user instanceof Authentication authentication
                        && authentication.getPrincipal()
                        instanceof RealtimePrincipal principal
        ) {
            return Optional.of(principal);
        }


        return Optional.empty();
    }
}
