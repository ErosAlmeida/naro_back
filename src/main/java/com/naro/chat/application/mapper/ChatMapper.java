package com.naro.chat.application.mapper;

import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.dto.ChatParticipantResponse;
import com.naro.chat.application.dto.ConversationResponse;

import com.naro.chat.domain.entity.ChatMessage;
import com.naro.chat.domain.entity.Conversation;

import com.naro.user.domain.entity.User;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ChatMapper {

    @Mapping(
            target = "conversationId",
            source = "conversation.id"
    )
    @Mapping(
            target = "senderId",
            source = "sender.id"
    )
    @Mapping(
            target = "senderUsername",
            source = "sender.username"
    )
    @Mapping(
            target = "iv",
            source = "encryptionIv"
    )
    @Mapping(
            target = "clientMessageId",
            ignore = true
    )
    ChatMessageResponse toMessageResponse(
            ChatMessage message
    );


    @Mapping(
            target = "id",
            source = "conversation.id"
    )
    @Mapping(
            target = "type",
            source = "conversation.type"
    )
    @Mapping(
            target = "participants",
            source = "conversation.participants"
    )
    @Mapping(
            target = "createdAt",
            source = "conversation.createdAt"
    )
    @Mapping(
            target = "updatedAt",
            source = "conversation.updatedAt"
    )
    @Mapping(
            target = "lastMessage",
            source = "lastMessage"
    )
    @Mapping(target = "canMessage", source = "canMessage")
    @Mapping(target = "unreadCount", ignore = true)
    @Mapping(target = "receipts", ignore = true)
    ConversationResponse toConversationResponse(
            Conversation conversation,
            ChatMessage lastMessage,
            boolean canMessage
    );

    default ConversationResponse toConversationResponse(Conversation conversation, ChatMessage lastMessage) {
        return toConversationResponse(conversation, lastMessage, false);
    }


    @Mapping(
            target = "userId",
            source = "id"
    )
    ChatParticipantResponse toParticipantResponse(
            User user
    );
}
