package com.naro.chat.presentation.websocket;

import com.naro.chat.application.dto.ChatNotificationResponse;
import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.event.ChatTypingEvent;
import com.naro.chat.application.event.ConversationReadEvent;
import com.naro.chat.application.event.MessageReceiptsEvent;

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
     * Aviso sem conteúdo para qualquer tela do Naro (badge, lista, toast).
     * O texto/ciphertext continua só em /queue/chat, aberto na área do chat.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void publishNotification(
            ChatMessageCreatedEvent event
    ) {

        ChatNotificationResponse notification =
                ChatNotificationResponse.message(event.message());


        for (
                String keycloakId :
                event.recipientKeycloakIds()
        ) {

            messagingTemplate.convertAndSendToUser(
                    keycloakId,
                    "/queue/notifications",
                    notification
            );
        }
    }


    /*
     * Só para o próprio leitor: as outras abas e aparelhos dele
     * zeram o contador da conversa.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void publishRead(
            ConversationReadEvent event
    ) {

        messagingTemplate.convertAndSendToUser(
                event.readerKeycloakId(),
                "/queue/notifications",
                ChatNotificationResponse.read(
                        event.conversationId(),
                        event.readAt()
                )
        );
    }


    /*
     * Para quem enviou: as mensagens dele chegaram ou foram lidas, e os
     * tiques mudam em todas as abas/aparelhos. Só metadados, como o resto
     * desta fila.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void publishReceipts(
            MessageReceiptsEvent event
    ) {

        messagingTemplate.convertAndSendToUser(
                event.recipientKeycloakId(),
                "/queue/notifications",
                ChatNotificationResponse.receipts(
                        event.conversationId(),
                        event.receipts(),
                        event.at()
                )
        );
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
