package com.naro.chat.presentation.websocket;

import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.event.ChatTypingEvent;

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


    /*
     * fallbackExecution: o evento também é enviado se, no futuro,
     * for publicado fora de uma transação.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void publishTyping(
            ChatTypingEvent event
    ) {

        for (
                String keycloakId :
                event.recipientKeycloakIds()
        ) {

            messagingTemplate.convertAndSendToUser(
                    keycloakId,
                    "/queue/typing",
                    event.typing()
            );
        }
    }
}
