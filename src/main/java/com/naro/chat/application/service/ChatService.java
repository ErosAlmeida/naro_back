package com.naro.chat.application.service;

import com.naro.chat.application.dto.ChatFocusRequest;
import com.naro.chat.application.dto.ChatMessageResponse;
import com.naro.chat.application.dto.ChatTypingRequest;
import com.naro.chat.application.dto.ConversationResponse;
import com.naro.chat.application.dto.SendChatMessageRequest;
import com.naro.chat.application.dto.UnreadChatsResponse;

import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ChatService {

    ConversationResponse createDirectConversation(
            UUID targetUserId,
            User currentUser
    );


    List<ConversationResponse> getConversations(
            User currentUser
    );


    Page<ChatMessageResponse> getMessages(
            UUID conversationId,
            User currentUser,
            Pageable pageable
    );


    void sendMessage(
            SendChatMessageRequest request,
            User sender
    );


    /**
     * Evento efêmero de "digitando...". Não persiste nada.
     */
    void notifyTyping(
            ChatTypingRequest request,
            User sender
    );


    /**
     * A sessão passou a mostrar uma conversa (ou nenhuma, com
     * conversationId null). Mostrar uma conversa a marca como lida.
     * Nunca lança por conversa inexistente ou alheia: só ignora o foco.
     */
    void updateFocus(
            ChatFocusRequest request,
            User user,
            String sessionId
    );


    UnreadChatsResponse getUnreadSummary(
            User currentUser
    );
}
