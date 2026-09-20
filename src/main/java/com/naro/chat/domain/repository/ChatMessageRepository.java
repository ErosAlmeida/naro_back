package com.naro.chat.domain.repository;

import com.naro.chat.domain.entity.ChatMessage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ChatMessageRepository
        extends JpaRepository<ChatMessage, UUID> {


    @EntityGraph(attributePaths = "sender")
    Page<ChatMessage>
    findByConversation_IdOrderByCreatedAtDesc(
            UUID conversationId,
            Pageable pageable
    );


    /*
     * Última mensagem de cada conversa, em uma única query
     * (usa idx_chat_messages_conversation_created).
     */
    @EntityGraph(attributePaths = "sender")
    @Query("""
            SELECT m
            FROM ChatMessage m
            WHERE m.conversation.id IN :conversationIds
              AND m.createdAt = (
                    SELECT MAX(latest.createdAt)
                    FROM ChatMessage latest
                    WHERE latest.conversation = m.conversation
              )
            """)
    List<ChatMessage> findLastMessages(
            @Param("conversationIds")
            Collection<UUID> conversationIds
    );
}
