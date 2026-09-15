package com.naro.chat.presentation.websocket;

import com.naro.chat.application.event.ChatMessageCreatedEvent;

import org.springframework.messaging.simp.SimpMessagingTemplate;

import org.springframework.stereotype.Component;

import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ChatRealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;


    public ChatRealtimePublisher(
            SimpMessagingTemplate messagingTemplate
    ) {
        this.messagingTemplate = messagingTemplate;
    }


    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void publish(
            ChatMessageCreatedEvent event
    ) {

        for (
                String keycloakId :
                event.recipientKeycloakIds()
        ) {

            messagingTemplate.convertAndSendToUser(
                    keycloakId,
                    "/queue/chat",
                    event.message()
            );
        }
    }
}