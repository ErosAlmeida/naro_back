package com.naro.notification.application.service;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.service.ConversationFocusRegistry;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.chat.domain.repository.UnreadSummary;
import com.naro.notification.application.dto.PushNotificationPayload;
import com.naro.notification.config.PushProperties;
import com.naro.notification.config.PushProperties.PreviewMode;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private PushNotificationService pushNotificationService;

    private final ConversationFocusRegistry focusRegistry = new ConversationFocusRegistry();

    private final User leonardo = user("leonardo", "Leonardo", "Vilela");

    private final User maria = user("maria", null, null);

    private final Conversation conversation = conversation(leonardo, maria);

    @BeforeEach
    void pushIsConfigured() {
        lenient().when(pushNotificationService.isEnabled()).thenReturn(true);
    }

    @Test
    void notifiesTheRecipientAndNeverTheSender() {
        recipientCanBeNotified(maria, 3);
        ChatMessageResponse message = message("  Você vai\nentrar hoje?  ", false);

        service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message));

        ArgumentCaptor<PushNotificationPayload> payload = ArgumentCaptor.forClass(PushNotificationPayload.class);
        verify(pushNotificationService).send(eq(maria.getId()), payload.capture());
        verify(pushNotificationService, never()).send(eq(leonardo.getId()), any());

        assertThat(payload.getValue().type()).isEqualTo("CHAT_MESSAGE");
        assertThat(payload.getValue().conversationId()).isEqualTo(conversation.getId());
        assertThat(payload.getValue().messageId()).isEqualTo(message.id());
        assertThat(payload.getValue().sender().displayName()).isEqualTo("Leonardo Vilela");
        assertThat(payload.getValue().sender().avatarUrl()).isNull();
        assertThat(payload.getValue().preview()).isEqualTo("Você vai entrar hoje?");
        assertThat(payload.getValue().url()).isEqualTo("/chat/" + conversation.getId());
        assertThat(payload.getValue().unreadConversations()).isEqualTo(3);
    }

    @Test
    void noPushWhileTheRecipientHasTheConversationOnScreen() {
        when(conversationRepository.findByIdWithParticipants(conversation.getId())).thenReturn(Optional.of(conversation));
        focusRegistry.focus(maria.getId(), "maria-laptop", conversation.getId());

        service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message("oi", false)));

        verify(pushNotificationService, never()).send(any(), any());
    }

    @Test
    void noPushForAMessageTheRecipientAlreadyRead() {
        ChatMessageResponse message = message("oi", false);
        when(conversationRepository.findByIdWithParticipants(conversation.getId())).thenReturn(Optional.of(conversation));
        when(conversationRepository.isReadSince(conversation.getId(), maria.getId(), message.createdAt())).thenReturn(true);

        service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message));

        verify(pushNotificationService, never()).send(any(), any());
    }

    @Test
    void endToEndEncryptedMessagesNeverCarryAPreview() {
        recipientCanBeNotified(maria, 1);

        service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message(null, true)));

        ArgumentCaptor<PushNotificationPayload> payload = ArgumentCaptor.forClass(PushNotificationPayload.class);
        verify(pushNotificationService).send(eq(maria.getId()), payload.capture());
        assertThat(payload.getValue().preview()).isNull();
        assertThat(payload.getValue().sender().displayName()).isEqualTo("Leonardo Vilela");
    }

    @Test
    void privacyModesHideTheTextOrEverything() {
        ChatMessageResponse message = message("segredo", false);

        PushNotificationPayload nameOnly = service(PreviewMode.NAME_ONLY, Runnable::run).payload(message, leonardo, 0);
        assertThat(nameOnly.sender().displayName()).isEqualTo("Leonardo Vilela");
        assertThat(nameOnly.preview()).isNull();

        PushNotificationPayload generic = service(PreviewMode.GENERIC, Runnable::run).payload(message, leonardo, 0);
        assertThat(generic.sender()).isNull();
        assertThat(generic.preview()).isNull();
        // Continua abrindo a conversa certa.
        assertThat(generic.conversationId()).isEqualTo(conversation.getId());
    }

    @Test
    void previewIsOneShortLine() {
        assertThat(NotificationService.preview("a\tb\r\nc\u0000d")).isEqualTo("a b c d");
        assertThat(NotificationService.preview("   ")).isNull();
        assertThat(NotificationService.preview(null)).isNull();

        String longText = "😀".repeat(NotificationService.PREVIEW_MAX_LENGTH + 10);
        String preview = NotificationService.preview(longText);
        assertThat(preview.codePointCount(0, preview.length())).isEqualTo(NotificationService.PREVIEW_MAX_LENGTH);
        assertThat(preview).endsWith("…");
    }

    @Test
    void theNameFallsBackToTheUsername() {
        assertThat(NotificationService.displayName(maria, "ignored")).isEqualTo("maria");
        assertThat(NotificationService.displayName(null, "leonardo")).isEqualTo("leonardo");
    }

    @Test
    void aFailingDeliveryNeverEscapesAndOtherRecipientsStillGetTheirs() {
        User ana = user("ana", "Ana", null);
        Conversation group = conversation(ConversationType.GROUP, leonardo, maria, ana);
        ChatMessageResponse message = message(group.getId(), "oi, grupo", false);
        when(conversationRepository.findByIdWithParticipants(group.getId())).thenReturn(Optional.of(group));
        when(chatMessageRepository.countUnread(any())).thenReturn(summary(1));
        doThrow(new IllegalStateException("push service down")).when(pushNotificationService).send(eq(maria.getId()), any());

        assertThatCode(() -> service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message)))
                .doesNotThrowAnyException();

        verify(pushNotificationService).send(eq(ana.getId()), any());
    }

    @Test
    void aFullQueueDropsThePushInsteadOfFailingTheMessage() {
        Executor full = _ -> {
            throw new RejectedExecutionException("queue full");
        };

        assertThatCode(() -> service(PreviewMode.NAME_AND_PREVIEW, full).onChatMessageCreated(created(message("oi", false))))
                .doesNotThrowAnyException();

        verify(pushNotificationService, never()).send(any(), any());
    }

    @Test
    void theDatabaseBeingDownOnlyCostsThePush() {
        when(conversationRepository.findByIdWithParticipants(conversation.getId())).thenThrow(new IllegalStateException("db"));

        assertThatCode(() -> service(PreviewMode.NAME_AND_PREVIEW, Runnable::run).onChatMessageCreated(created(message("oi", false))))
                .doesNotThrowAnyException();
    }

    @Test
    void withoutWebPushConfiguredNothingIsScheduled() {
        when(pushNotificationService.isEnabled()).thenReturn(false);
        Executor executor = mock(Executor.class);

        service(PreviewMode.NAME_AND_PREVIEW, executor).onChatMessageCreated(created(message("oi", false)));

        verifyNoInteractions(executor, conversationRepository, chatMessageRepository);
    }

    private void recipientCanBeNotified(User recipient, long unreadConversations) {
        when(conversationRepository.findByIdWithParticipants(conversation.getId())).thenReturn(Optional.of(conversation));
        when(conversationRepository.isReadSince(eq(conversation.getId()), eq(recipient.getId()), any())).thenReturn(false);
        when(chatMessageRepository.countUnread(recipient.getId())).thenReturn(summary(unreadConversations));
    }

    private NotificationService service(PreviewMode mode, Executor executor) {
        PushProperties properties = new PushProperties(
                new PushProperties.Vapid(null, null, null), mode, List.of(), Duration.ofHours(24), 10);

        return new NotificationService(conversationRepository, chatMessageRepository, focusRegistry,
                pushNotificationService, properties, executor);
    }

    private ChatMessageResponse message(String content, boolean encrypted) {
        return message(conversation.getId(), content, encrypted);
    }

    private ChatMessageResponse message(UUID conversationId, String content, boolean encrypted) {
        return new ChatMessageResponse(
                UUID.randomUUID(), conversationId, leonardo.getId(), leonardo.getUsername(),
                encrypted ? null : content, encrypted,
                encrypted ? "Y2lwaGVydGV4dA" : null, encrypted ? "aXY" : null, encrypted ? 1 : null,
                Instant.now(), null);
    }

    private static ChatMessageCreatedEvent created(ChatMessageResponse message) {
        return new ChatMessageCreatedEvent(message, Set.of("kc-leonardo", "kc-maria"));
    }

    private static UnreadSummary summary(long conversations) {
        return new UnreadSummary() {
            @Override
            public long getConversations() {
                return conversations;
            }

            @Override
            public long getMessages() {
                return conversations;
            }
        };
    }

    private static User user(String username, String firstName, String lastName) {
        User user = new User("kc-" + username, username, null, firstName, lastName);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Conversation conversation(User... participants) {
        return conversation(ConversationType.DIRECT, participants);
    }

    private static Conversation conversation(ConversationType type, User... participants) {
        Conversation conversation = new Conversation(type, Set.of(participants));
        ReflectionTestUtils.setField(conversation, "id", UUID.randomUUID());
        return conversation;
    }
}
