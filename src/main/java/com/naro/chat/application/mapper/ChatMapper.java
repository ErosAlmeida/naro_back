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
    ChatMessageResponse toMessageResponse(
            ChatMessage message
    );


    ConversationResponse toConversationResponse(
            Conversation conversation
    );


    @Mapping(
            target = "userId",
            source = "id"
    )
    ChatParticipantResponse toParticipantResponse(
            User user
    );
}