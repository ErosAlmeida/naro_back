package com.naro.presence.presentation.websocket;

import com.naro.presence.application.event.PresenceChangedEvent;

import org.springframework.messaging.simp.SimpMessagingTemplate;

import org.springframework.stereotype.Component;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PresenceRealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;


    public PresenceRealtimePublisher(
            SimpMessagingTemplate messagingTemplate
    ) {
        this.messagingTemplate = messagingTemplate;
    }


    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void publish(
            PresenceChangedEvent event
    ) {

        for (
                String keycloakId :
                event.recipientKeycloakIds()
        ) {

            messagingTemplate.convertAndSendToUser(
                    keycloakId,
                    "/queue/presence",
                    event.presence()
            );
        }
    }
}
