package com.naro.chat.presentation.websocket;

import com.naro.chat.application.service.ConversationFocusRegistry;
import com.naro.security.RealtimePrincipal;

import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Sessão encerrada (aba fechada, rede caiu, heartbeat perdido) não está
 * mais vendo conversa nenhuma: volta a receber push.
 */
@Component
public class ChatFocusSessionListener {

    private final ConversationFocusRegistry focusRegistry;

    public ChatFocusSessionListener(ConversationFocusRegistry focusRegistry) {
        this.focusRegistry = focusRegistry;
    }

    /* Pode chegar mais de uma vez para a mesma sessão; clear é idempotente. */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        if (event.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof RealtimePrincipal principal) {
            focusRegistry.clear(principal.userId(), event.getSessionId());
        }
    }
}
