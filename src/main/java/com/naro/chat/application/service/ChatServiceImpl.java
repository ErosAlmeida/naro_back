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
import com.naro.exception.NotFoundException;

import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.context.ApplicationEventPublisher;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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


    public ChatServiceImpl(
            ConversationRepository conversationRepository,
            ChatMessageRepository chatMessageRepository,
            UserRepository userRepository,
            ChatMapper chatMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.userRepository = userRepository;
        this.chatMapper = chatMapper;
        this.eventPublisher = eventPublisher;
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


        return conversationRepository
                .findDirectConversation(
                        ConversationType.DIRECT,
                        currentUser.getId(),
                        targetUserId
                )
                .map(
                        this::toConversationResponse
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
                            .toConversationResponse(saved, null);
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


        return conversations
                .stream()
                .map(conversation ->
                        chatMapper.toConversationResponse(
                                conversation,
                                lastMessages.get(conversation.getId())
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

        Conversation conversation =
                getConversationForMember(
                        request.conversationId(),
                        sender
                );


        User senderReference =
                userRepository.getReferenceById(
                        sender.getId()
                );


        ChatMessage message =
                new ChatMessage(
                        conversation,
                        senderReference,
                        request.content().trim()
                );


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
    @Transactional(readOnly = true)
    public void notifyTyping(
            ChatTypingRequest request,
            User sender
    ) {

        Conversation conversation =
                getConversationForMember(
                        request.conversationId(),
                        sender
                );


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
            Conversation conversation
    ) {

        return chatMapper.toConversationResponse(
                conversation,
                findLastMessages(
                        List.of(conversation.getId())
                ).get(conversation.getId())
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
}