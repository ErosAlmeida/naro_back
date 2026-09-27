package com.naro.chat.application.service;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.dto.ChatTypingRequest;
import com.naro.chat.application.dto.ChatTypingResponse;
import com.naro.chat.application.dto.ConversationResponse;
import com.naro.chat.application.dto.SendChatMessageRequest;

import com.naro.chat.application.event.ChatMessageCreatedEvent;
import com.naro.chat.application.event.ChatTypingEvent;
import com.naro.chat.application.mapper.ChatMapper;

import com.naro.chat.domain.entity.ChatMessage;
import com.naro.chat.domain.entity.Conversation;

import com.naro.chat.domain.enums.ConversationType;

import com.naro.chat.domain.repository.ChatMessageRepository;
import com.naro.chat.domain.repository.ConversationRepository;

import com.naro.exception.BadRequestException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.ChatFriendshipRequiredException;
import com.naro.exception.NotFoundException;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.repository.FriendshipRepository;

import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.context.ApplicationEventPublisher;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChatServiceImpl
        implements ChatService {

    private final ConversationRepository conversationRepository;

    private final ChatMessageRepository chatMessageRepository;

    private final UserRepository userRepository;

    private final ChatMapper chatMapper;

    private final ApplicationEventPublisher eventPublisher;

    private final FriendshipRepository friendshipRepository;

    private final ChatMessagePayloadPolicy payloadPolicy;


    public ChatServiceImpl(
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            UserRepository userRepository,
            ChatMapper chatMapper,
            ApplicationEventPublisher eventPublisher,
            FriendshipRepository friendshipRepository,
            ChatMessagePayloadPolicy payloadPolicy
    ) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.chatMapper = chatMapper;
        this.eventPublisher = eventPublisher;
        this.friendshipRepository = friendshipRepository;
        this.payloadPolicy = payloadPolicy;
    }


    @Override
    @Transactional
    public ConversationResponse createDirectConversation(
            UUID targetUserId,
            User currentUser
    ) {

        if (currentUser.getId().equals(targetUserId)) {

            throw new BadRequestException(
                    "You cannot create a conversation with yourself"
            );
        }


        User targetUser =
                userRepository
                        .findById(targetUserId)
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Target user not found"
                                )
                        );

        requireRealParticipants(Set.of(currentUser, targetUser));
        lockUsers(List.of(currentUser.getId(), targetUserId));
        requireFriendship(currentUser.getId(), targetUserId, null, null);

        return conversationRepository
                .findDirectConversation(
                        ConversationType.DIRECT,
                        currentUser.getId(),
                        targetUserId
                )
                .map(
                        conversation -> {
                            requireValidParticipants(conversation, currentUser);
                            return toConversationResponse(conversation, true);
                        }
                )
                .orElseGet(() -> {

                    User currentUserReference =
                            userRepository.getReferenceById(
                                    currentUser.getId()
                            );


                    Conversation conversation =
                            new Conversation(
                                    ConversationType.DIRECT,
                                    Set.of(
                                            currentUserReference,
                                            targetUser
                                    )
                            );


                    Conversation saved =
                            conversationRepository.save(
                                    conversation
                            );


                    return chatMapper
                            .toConversationResponse(saved, null, true);
                });
    }


    @Override
    @Transactional(readOnly = true)
    public List<ConversationResponse> getConversations(
            User currentUser
    ) {

        List<Conversation> conversations =
                conversationRepository
                        .findAllByParticipantId(
                                currentUser.getId()
                        );


        Map<UUID, ChatMessage> lastMessages =
                findLastMessages(
                        conversations
                                .stream()
                                .map(Conversation::getId)
                                .toList()
                );

        Set<UUID> participantIds = conversations.stream()
                .flatMap(conversation -> conversation.getParticipants().stream())
                .map(User::getId)
                .filter(id -> !id.equals(currentUser.getId()))
                .collect(Collectors.toSet());
        Set<UUID> friendIds = participantIds.isEmpty() ? Set.of() :
                friendshipRepository.findFriendshipsAmong(currentUser.getId(), participantIds).stream()
                        .map(friendship -> otherFriendId(friendship, currentUser.getId()))
                        .collect(Collectors.toSet());

        return conversations
                .stream()
                .map(conversation ->
                        chatMapper.toConversationResponse(
                                conversation,
                                lastMessages.get(conversation.getId()),
                                canMessage(conversation, currentUser, friendIds)
                        )
                )
                .toList();
    }


    @Override
    @Transactional(readOnly = true)
    public Page<ChatMessageResponse> getMessages(
            UUID conversationId,
            User currentUser,
            Pageable pageable
    ) {

        getConversationForMember(
                conversationId,
                currentUser
        );


        return chatMessageRepository
                .findByConversation_IdOrderByCreatedAtDesc(
                        conversationId,
                        pageable
                )
                .map(
                        chatMapper::toMessageResponse
                );
    }


    @Override
    @Transactional
    public void sendMessage(
            SendChatMessageRequest request,
            User sender
    ) {

        /*
         * Antes de qualquer acesso ao banco: com o E2EE obrigatório,
         * uma mensagem sem criptografia é recusada aqui e nunca persiste.
         */
        ChatMessagePayload payload =
                payloadPolicy.validate(request);

        Conversation conversation =
                getConversationForMember(
                        request.conversationId(),
                        sender
                );

        requireMessagingAllowed(conversation, sender, request.clientMessageId());

        User senderReference =
                userRepository.getReferenceById(
                        sender.getId()
                );


        ChatMessage message =
                switch (payload) {
                    case ChatMessagePayload.Encrypted encrypted -> ChatMessage.encrypted(
                            conversation,
                            senderReference,
                            encrypted.ciphertext(),
                            encrypted.iv(),
                            encrypted.version()
                    );
                    case ChatMessagePayload.Plaintext plaintext -> new ChatMessage(
                            conversation,
                            senderReference,
                            plaintext.content()
                    );
                };


        ChatMessage saved =
                chatMessageRepository.saveAndFlush(
                        message
                );


        conversation.touch();


        ChatMessageResponse response =
                chatMapper
                        .toMessageResponse(saved)
                        .withClientMessageId(
                                request.clientMessageId()
                        );


        Set<String> recipients =
                conversation
                        .getParticipants()
                        .stream()
                        .map(User::getKeycloakId)
                        .collect(
                                Collectors.toUnmodifiableSet()
                        );


        eventPublisher.publishEvent(
                new ChatMessageCreatedEvent(
                        response,
                        recipients
                )
        );
    }


    /*
     * O remetente vem da conexão autenticada; o request
     * não carrega identidade. Só participantes podem
     * sinalizar digitação, e só os outros participantes
     * recebem o evento.
     */
    @Override
    @Transactional
    public void notifyTyping(
            ChatTypingRequest request,
            User sender
    ) {

        Conversation conversation =
                getConversationForMember(
                        request.conversationId(),
                        sender
                );

        requireMessagingAllowed(conversation, sender, null);

        Set<String> recipients =
                conversation
                        .getParticipants()
                        .stream()
                        .filter(participant ->
                                !participant
                                        .getId()
                                        .equals(sender.getId())
                        )
                        .map(User::getKeycloakId)
                        .collect(
                                Collectors.toUnmodifiableSet()
                        );


        if (recipients.isEmpty()) {
            return;
        }


        eventPublisher.publishEvent(
                new ChatTypingEvent(
                        new ChatTypingResponse(
                                conversation.getId(),
                                sender.getId(),
                                sender.getUsername(),
                                request.typing()
                        ),
                        recipients
                )
        );
    }


    private ConversationResponse toConversationResponse(
            Conversation conversation,
            boolean canMessage
    ) {

        return chatMapper.toConversationResponse(
                conversation,
                findLastMessages(
                        List.of(conversation.getId())
                ).get(conversation.getId()),
                canMessage
        );
    }


    /*
     * Uma query para todas as conversas da lista (sem N+1).
     */
    private Map<UUID, ChatMessage> findLastMessages(
            List<UUID> conversationIds
    ) {

        if (conversationIds.isEmpty()) {
            return Map.of();
        }


        return chatMessageRepository
                .findLastMessages(conversationIds)
                .stream()
                .collect(
                        Collectors.toMap(
                                message -> message.getConversation().getId(),
                                Function.identity(),
                                (first, second) -> first
                        )
                );
    }


    private Conversation getConversationForMember(
            UUID conversationId,
            User user
    ) {

        Conversation conversation =
                conversationRepository
                        .findByIdWithParticipants(
                                conversationId
                        )
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Conversation not found"
                                )
                        );


        boolean member =
                conversation
                        .getParticipants()
                        .stream()
                        .anyMatch(
                                participant ->
                                        participant
                                                .getId()
                                                .equals(
                                                        user.getId()
                                                )
                        );


        if (!member) {

            throw new ForbiddenException(
                    "You are not a participant of this conversation"
            );
        }


        return conversation;
    }

    private void requireMessagingAllowed(Conversation conversation, User sender, UUID clientMessageId) {
        requireValidParticipants(conversation, sender);
        requireRealParticipants(conversation.getParticipants());
        // Same ordering as FriendServiceImpl: create/send/typing and friendship
        // removal serialize on the user rows, including an already connected socket.
        lockUsers(conversation.getParticipants().stream().map(User::getId).toList());
        conversation.getParticipants().stream()
                .filter(participant -> !participant.getId().equals(sender.getId()))
                .forEach(participant -> requireFriendship(sender.getId(), participant.getId(),
                        conversation.getId(), clientMessageId));
    }

    private void lockUsers(Collection<UUID> ids) {
        ids.stream().distinct().sorted(Comparator.comparing(UUID::toString)).forEach(id ->
                userRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new NotFoundException("User not found")));
    }

    private void requireFriendship(UUID senderId, UUID recipientId, UUID conversationId, UUID clientMessageId) {
        if (!friendshipRepository.existsFriendshipBetweenUsers(senderId, recipientId)) {
            throw new ChatFriendshipRequiredException(conversationId, clientMessageId);
        }
    }

    private static void requireRealParticipants(Collection<User> participants) {
        if (participants.stream().anyMatch(participant -> !isRealUser(participant))) {
            throw new ForbiddenException("You can only message real friends");
        }
    }

    private static boolean isRealUser(User participant) {
        String subject = participant.getKeycloakId();
        return subject != null && !subject.startsWith("seed:") && !subject.startsWith("friend-seed:");
    }

    private static void requireValidParticipants(Conversation conversation, User sender) {
        if (!hasValidParticipants(conversation, sender)) {
            throw new ForbiddenException("Conversation has invalid participants");
        }
    }

    private static boolean hasValidParticipants(Conversation conversation, User sender) {
        Set<User> participants = conversation.getParticipants();
        return participants.stream().anyMatch(participant -> participant.getId().equals(sender.getId()))
                && ((conversation.getType() == ConversationType.DIRECT && participants.size() == 2)
                || (conversation.getType() == ConversationType.GROUP && participants.size() >= 2));
    }

    private static boolean canMessage(Conversation conversation, User sender, Set<UUID> friendIds) {
        return hasValidParticipants(conversation, sender)
                && conversation.getParticipants().stream().allMatch(ChatServiceImpl::isRealUser)
                && conversation.getParticipants().stream()
                        .filter(participant -> !participant.getId().equals(sender.getId()))
                        .allMatch(participant -> friendIds.contains(participant.getId()));
    }

    private static UUID otherFriendId(Friendship friendship, UUID currentUserId) {
        return friendship.getUserA().getId().equals(currentUserId)
                ? friendship.getUserB().getId() : friendship.getUserA().getId();
    }
}
