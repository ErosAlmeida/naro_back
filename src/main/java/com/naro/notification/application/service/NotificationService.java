package com.naro.notification.application.service;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.service.ConversationFocusRegistry;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.notification.application.dto.PushNotificationPayload;
import com.naro.notification.config.PushConfiguration;
import com.naro.notification.config.PushProperties;
import com.naro.notification.config.PushProperties.PreviewMode;
import com.naro.user.domain.entity.User;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Decide quem recebe push de uma mensagem nova, e com qual conteúdo.
 *
 * Fluxo: o chat salva e entrega a mensagem pelo WebSocket; só depois do
 * commit este listener agenda o push em outra thread. Nada aqui atrasa,
 * desfaz ou impede o envio da mensagem: falhas viram log.
 *
 * Só mensagens novas geram push. Digitação, presença, heartbeat, leitura e
 * erros do WebSocket são outros eventos e nunca passam por aqui.
 *
 * Um destinatário não recebe push quando:
 *   - é o próprio remetente;
 *   - alguma sessão dele (qualquer aparelho) está com a conversa na tela;
 *   - já leu a conversa depois da mensagem;
 *   - não tem aparelho inscrito (PushNotificationService não envia nada).
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    static final int PREVIEW_MAX_LENGTH = 120;

    static final int NAME_MAX_LENGTH = 60;

    private final ConversationRepository conversationRepository;

    private final ChatMessageRepository chatMessageRepository;

    private final ConversationFocusRegistry focusRegistry;

    private final PushNotificationService pushNotificationService;

    private final PushProperties properties;

    private final Executor executor;


    public NotificationService(
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            ConversationFocusRegistry focusRegistry,
            PushNotificationService pushNotificationService,
            PushProperties properties,
            @Qualifier(PushConfiguration.PUSH_EXECUTOR) Executor executor
    ) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.focusRegistry = focusRegistry;
        this.pushNotificationService = pushNotificationService;
        this.properties = properties;
        this.executor = executor;
    }


    /*
     * Nunca lança: roda dentro do commit do envio. Fila cheia descarta o
     * push (o chat segue) em vez de travar a thread do WebSocket.
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT
    )
    public void onChatMessageCreated(ChatMessageCreatedEvent event) {

        if (!pushNotificationService.isEnabled()) {
            return;
        }

        ChatMessageResponse message = event.message();

        try {
            executor.execute(() -> notifyRecipients(message));
        } catch (RuntimeException exception) {
            log.warn("Push for message {} dropped ({})", message.id(), exception.getClass().getSimpleName());
        }
    }


    void notifyRecipients(ChatMessageResponse message) {

        Conversation conversation;

        try {
            conversation = conversationRepository
                    .findByIdWithParticipants(message.conversationId())
                    .orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Push for message {} skipped ({})", message.id(), exception.getClass().getSimpleName());
            return;
        }

        if (conversation == null) {
            return;
        }

        User sender = conversation.getParticipants().stream()
                .filter(participant -> participant.getId().equals(message.senderId()))
                .findFirst()
                .orElse(null);

        for (User recipient : conversation.getParticipants()) {

            if (recipient.getId().equals(message.senderId())) {
                continue;
            }

            // Um destinatário com problema não impede os outros.
            try {
                if (shouldPush(recipient, message)) {
                    long unreadConversations = chatMessageRepository.countUnread(recipient.getId()).getConversations();
                    pushNotificationService.send(recipient.getId(), payload(message, sender, unreadConversations));
                }
            } catch (RuntimeException exception) {
                log.warn("Push for message {} to user {} failed ({})",
                        message.id(), recipient.getId(), exception.getClass().getSimpleName());
            }
        }
    }


    private boolean shouldPush(User recipient, ChatMessageResponse message) {

        if (focusRegistry.isViewing(recipient.getId(), message.conversationId())) {
            return false;
        }

        return !conversationRepository.isReadSince(message.conversationId(), recipient.getId(), message.createdAt());
    }


    PushNotificationPayload payload(ChatMessageResponse message, User sender, long unreadConversations) {

        PreviewMode mode = properties.preview();

        PushNotificationPayload.Sender senderInfo = mode == PreviewMode.GENERIC
                ? null
                : new PushNotificationPayload.Sender(
                        displayName(sender, message.senderUsername()),
                        sender == null ? null : httpsOrNull(sender.getAvatarUrl())
                );

        // E2EE: o servidor não tem o texto, então nunca há prévia.
        String preview = mode == PreviewMode.NAME_AND_PREVIEW && !message.encrypted()
                ? preview(message.content())
                : null;

        return new PushNotificationPayload(
                PushNotificationPayload.CHAT_MESSAGE,
                message.conversationId(),
                message.id(),
                senderInfo,
                preview,
                "/chat/" + message.conversationId(),
                message.createdAt(),
                unreadConversations
        );
    }


    /* Nome como o frontend mostra: nome completo ou, sem ele, o username. */
    static String displayName(User sender, String fallbackUsername) {
        String fullName = sender == null ? "" : Stream.of(sender.getFirstName(), sender.getLastName())
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));

        String name = fullName.isEmpty()
                ? (sender == null ? fallbackUsername : sender.getUsername())
                : fullName;

        return truncate(singleLine(name == null ? "" : name), NAME_MAX_LENGTH);
    }


    /* Uma linha, sem caracteres de controle, com reticências acima do limite. */
    static String preview(String content) {
        if (content == null) {
            return null;
        }

        String text = singleLine(content);

        return text.isEmpty() ? null : truncate(text, PREVIEW_MAX_LENGTH);
    }


    private static String singleLine(String value) {
        return value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }


    /* Conta code points: nunca corta um emoji ao meio. */
    private static String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }

        int end = value.offsetByCodePoints(0, maxCodePoints - 1);

        return value.substring(0, end).stripTrailing() + "…";
    }


    private static String httpsOrNull(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }
}
