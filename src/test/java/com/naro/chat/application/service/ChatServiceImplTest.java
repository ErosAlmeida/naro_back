package com.naro.chat.application.service;

import com.naro.chat.application.dto.ChatTypingRequest;
import com.naro.chat.application.dto.ConversationResponse;
import com.naro.chat.application.dto.SendChatMessageRequest;
import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.event.ChatTypingEvent;
import com.naro.chat.application.mapper.ChatMapperImpl;
import com.naro.chat.domain.entity.ChatMessage;
import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;
import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;
import com.naro.exception.BadRequestException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FriendshipRepository friendshipRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ChatServiceImpl chatService;

    private final User currentUser = user("leonardo");

    private final User otherUser = user("maria");


    @BeforeEach
    void setUp() {
        chatService = new ChatServiceImpl(
                conversationRepository,
                chatMessageRepository,
                userRepository,
                new ChatMapperImpl(),
                eventPublisher,
                friendshipRepository
        );
    }


    @Test
    void createDirectConversationWithYourselfThrowsBadRequest() {

        assertThatThrownBy(() ->
                chatService.createDirectConversation(currentUser.getId(), currentUser)
        )
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(conversationRepository);
    }


    @Test
    void createDirectConversationWithUnknownUserThrowsNotFound() {

        UUID unknownUserId = UUID.randomUUID();

        when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                chatService.createDirectConversation(unknownUserId, currentUser)
        )
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Target user not found");
    }


    @Test
    void createDirectConversationReturnsExistingConversation() {

        Conversation existing = conversation(currentUser, otherUser);

        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        allowMessaging(currentUser, otherUser);
        when(conversationRepository.findDirectConversation(
                ConversationType.DIRECT, currentUser.getId(), otherUser.getId()
        )).thenReturn(Optional.of(existing));

        ConversationResponse response =
                chatService.createDirectConversation(otherUser.getId(), currentUser);

        assertThat(response.id()).isEqualTo(existing.getId());
        assertThat(response.canMessage()).isTrue();
        verify(conversationRepository, never()).save(any());
    }


    @Test
    void createDirectConversationCreatesNewConversationWithBothParticipants() {

        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        allowMessaging(currentUser, otherUser);
        when(conversationRepository.findDirectConversation(
                ConversationType.DIRECT, currentUser.getId(), otherUser.getId()
        )).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(currentUser.getId())).thenReturn(currentUser);
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));

        ConversationResponse response =
                chatService.createDirectConversation(otherUser.getId(), currentUser);

        assertThat(response.type()).isEqualTo(ConversationType.DIRECT);
        assertThat(response.canMessage()).isTrue();
        assertThat(response.participants())
                .extracting(participant -> participant.username())
                .containsExactlyInAnyOrder("leonardo", "maria");
    }


    @Test
    void getMessagesOfUnknownConversationThrowsNotFound() {

        UUID conversationId = UUID.randomUUID();

        when(conversationRepository.findByIdWithParticipants(conversationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                chatService.getMessages(conversationId, currentUser, PageRequest.of(0, 50))
        )
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Conversation not found");
    }


    @Test
    void getMessagesAsNonParticipantThrowsForbidden() {

        Conversation conversation = conversation(otherUser, user("joao"));

        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() ->
                chatService.getMessages(conversation.getId(), currentUser, PageRequest.of(0, 50))
        )
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(chatMessageRepository);
    }


    @Test
    void sendMessagePersistsTrimmedContentAndNotifiesParticipants() {

        Conversation conversation = conversation(currentUser, otherUser);
        UUID clientMessageId = UUID.randomUUID();
        allowMessaging(currentUser, otherUser);

        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(userRepository.getReferenceById(currentUser.getId())).thenReturn(currentUser);
        when(chatMessageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));

        chatService.sendMessage(
                new SendChatMessageRequest(conversation.getId(), "  Oi!  ", clientMessageId),
                currentUser
        );

        ArgumentCaptor<ChatMessageCreatedEvent> captor =
                ArgumentCaptor.forClass(ChatMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ChatMessageCreatedEvent event = captor.getValue();
        assertThat(event.message().content()).isEqualTo("Oi!");
        assertThat(event.message().senderId()).isEqualTo(currentUser.getId());
        assertThat(event.message().clientMessageId()).isEqualTo(clientMessageId);
        assertThat(event.recipientKeycloakIds())
                .containsExactlyInAnyOrder(currentUser.getKeycloakId(), otherUser.getKeycloakId());
    }


    @Test
    void sendMessageAsNonParticipantThrowsForbiddenAndPublishesNothing() {

        Conversation conversation = conversation(otherUser, user("joao"));

        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> chatService.sendMessage(
                new SendChatMessageRequest(conversation.getId(), "Oi", null),
                currentUser
        ))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(chatMessageRepository, eventPublisher);
    }


    @Test
    void getConversationsIncludesLastMessageLoadedInOneQuery() {

        Conversation withMessages = conversation(currentUser, otherUser);
        Conversation empty = conversation(currentUser, user("joao"));
        ChatMessage last = withId(new ChatMessage(withMessages, otherUser, "Até amanhã"));

        when(conversationRepository.findAllByParticipantId(currentUser.getId()))
                .thenReturn(List.of(withMessages, empty));
        when(chatMessageRepository.findLastMessages(List.of(withMessages.getId(), empty.getId())))
                .thenReturn(List.of(last));
        when(friendshipRepository.findFriendshipsAmong(org.mockito.ArgumentMatchers.eq(currentUser.getId()), any()))
                .thenReturn(List.of(Friendship.between(currentUser, otherUser)));

        List<ConversationResponse> conversations = chatService.getConversations(currentUser);

        assertThat(conversations).hasSize(2);
        assertThat(conversations.get(0).lastMessage().content()).isEqualTo("Até amanhã");
        assertThat(conversations.get(0).lastMessage().senderUsername()).isEqualTo("maria");
        assertThat(conversations.get(1).lastMessage()).isNull();
        assertThat(conversations.get(0).canMessage()).isTrue();
        assertThat(conversations.get(1).canMessage()).isFalse();
        verify(friendshipRepository, times(1)).findFriendshipsAmong(org.mockito.ArgumentMatchers.eq(currentUser.getId()), any());
        verify(friendshipRepository, never()).existsFriendshipBetweenUsers(any(), any());
    }


    @Test
    void notifyTypingSendsEventOnlyToOtherParticipantsWithAuthenticatedSender() {

        User third = user("joao");
        Conversation conversation =
                withId(new Conversation(ConversationType.GROUP, Set.of(currentUser, otherUser, third)));
        allowMessaging(currentUser, otherUser, third);

        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        chatService.notifyTyping(new ChatTypingRequest(conversation.getId(), true), currentUser);

        ArgumentCaptor<ChatTypingEvent> captor = ArgumentCaptor.forClass(ChatTypingEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ChatTypingEvent event = captor.getValue();
        assertThat(event.typing().conversationId()).isEqualTo(conversation.getId());
        assertThat(event.typing().userId()).isEqualTo(currentUser.getId());
        assertThat(event.typing().username()).isEqualTo("leonardo");
        assertThat(event.typing().typing()).isTrue();
        assertThat(event.recipientKeycloakIds())
                .containsExactlyInAnyOrder(otherUser.getKeycloakId(), third.getKeycloakId())
                .doesNotContain(currentUser.getKeycloakId());

        verifyNoInteractions(chatMessageRepository);
    }


    @Test
    void notifyTypingAsNonParticipantThrowsForbiddenAndPublishesNothing() {

        Conversation conversation = conversation(otherUser, user("joao"));

        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() ->
                chatService.notifyTyping(new ChatTypingRequest(conversation.getId(), true), currentUser)
        )
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void nonFriendsCannotCreateOrReuseConversationEvenWithPendingRequest() {
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        lockable(currentUser, otherUser);
        // Only an accepted friendship authorizes chat; pending requests and
        // follows do not create a row in the friendship repository.
        when(friendshipRepository.existsFriendshipBetweenUsers(currentUser.getId(), otherUser.getId()))
                .thenReturn(false);

        assertThatThrownBy(() -> chatService.createDirectConversation(otherUser.getId(), currentUser))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("friends");

        verifyNoInteractions(conversationRepository, chatMessageRepository, eventPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed:old", "friend-seed:old"})
    void seededUserCannotStartConversation(String subject) {
        ReflectionTestUtils.setField(otherUser, "keycloakId", subject);
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));

        assertThatThrownBy(() -> chatService.createDirectConversation(otherUser.getId(), currentUser))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(friendshipRepository, conversationRepository, eventPublisher);
    }

    @Test
    void friendshipRemovalBlocksNextMessageAndTypingOnExistingConversation() {
        Conversation conversation = conversation(currentUser, otherUser);
        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        lockable(currentUser, otherUser);
        when(friendshipRepository.existsFriendshipBetweenUsers(currentUser.getId(), otherUser.getId()))
                .thenReturn(true, false);
        when(userRepository.getReferenceById(currentUser.getId())).thenReturn(currentUser);
        when(chatMessageRepository.saveAndFlush(any(ChatMessage.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));

        chatService.sendMessage(new SendChatMessageRequest(conversation.getId(), "Antes", null), currentUser);
        assertThatThrownBy(() -> chatService.sendMessage(
                new SendChatMessageRequest(conversation.getId(), "Depois", null), currentUser))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> chatService.notifyTyping(
                new ChatTypingRequest(conversation.getId(), true), currentUser))
                .isInstanceOf(ForbiddenException.class);

        verify(chatMessageRepository, times(1)).saveAndFlush(any(ChatMessage.class));
        verify(eventPublisher, times(1)).publishEvent(any(ChatMessageCreatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(ChatTypingEvent.class));
    }

    @Test
    void formerFriendsCanStillReadHistory() {
        Conversation conversation = conversation(currentUser, otherUser);
        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        when(chatMessageRepository.findByConversation_IdOrderByCreatedAtDesc(conversation.getId(), PageRequest.of(0, 50)))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(chatService.getMessages(conversation.getId(), currentUser, PageRequest.of(0, 50))).isEmpty();
        verifyNoInteractions(friendshipRepository);
    }

    @Test
    void groupCannotSendWhenAnyRecipientIsNotAFriend() {
        User third = user("joao");
        Conversation conversation = withId(new Conversation(ConversationType.GROUP,
                Set.of(currentUser, otherUser, third)));
        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));
        lockable(currentUser, otherUser, third);
        when(friendshipRepository.existsFriendshipBetweenUsers(org.mockito.ArgumentMatchers.eq(currentUser.getId()), any()))
                .thenAnswer(invocation -> invocation.getArgument(1).equals(otherUser.getId()));

        assertThatThrownBy(() -> chatService.sendMessage(
                new SendChatMessageRequest(conversation.getId(), "Grupo", null), currentUser))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(chatMessageRepository, eventPublisher);
    }

    @Test
    void malformedDirectCannotSendOrNotifyTyping() {
        Conversation conversation = withId(new Conversation(ConversationType.DIRECT,
                Set.of(currentUser, otherUser, user("joao"))));
        when(conversationRepository.findByIdWithParticipants(conversation.getId()))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> chatService.sendMessage(
                new SendChatMessageRequest(conversation.getId(), "Oi", null), currentUser))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> chatService.notifyTyping(new ChatTypingRequest(conversation.getId(), true), currentUser))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(userRepository, friendshipRepository, chatMessageRepository, eventPublisher);
    }

    @Test
    void usersAreLockedInFriendServiceUuidOrderBeforeFriendshipCheck() {
        when(userRepository.findById(otherUser.getId())).thenReturn(Optional.of(otherUser));
        lockable(currentUser, otherUser);
        assertThatThrownBy(() -> chatService.createDirectConversation(otherUser.getId(), currentUser))
                .isInstanceOf(ForbiddenException.class);

        List<UUID> ordered = List.of(currentUser.getId(), otherUser.getId()).stream()
                .sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        var order = inOrder(userRepository, friendshipRepository);
        order.verify(userRepository).findById(otherUser.getId());
        order.verify(userRepository).findByIdForUpdate(ordered.get(0));
        order.verify(userRepository).findByIdForUpdate(ordered.get(1));
        order.verify(friendshipRepository).existsFriendshipBetweenUsers(currentUser.getId(), otherUser.getId());
    }

    private void allowMessaging(User sender, User... recipients) {
        lockable(sender);
        lockable(recipients);
        for (User recipient : recipients) {
            when(friendshipRepository.existsFriendshipBetweenUsers(sender.getId(), recipient.getId())).thenReturn(true);
        }
    }

    private void lockable(User... users) {
        for (User user : users) {
            when(userRepository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        }
    }


    private static Conversation conversation(User first, User second) {
        return withId(new Conversation(ConversationType.DIRECT, Set.of(first, second)));
    }


    private static <T> T withId(T entity) {

        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(entity, "createdAt", Instant.now());

        return entity;
    }


    private static User user(String username) {

        User user = new User(UUID.randomUUID().toString(), username, null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
