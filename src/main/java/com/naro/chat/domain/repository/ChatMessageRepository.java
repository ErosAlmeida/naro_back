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


    /*
     * Não lida = de outra pessoa e criada depois da última leitura
     * (sem leitura registrada, todas). Conversas sem não lidas não
     * aparecem no resultado. Usa idx_chat_messages_conversation_created.
     */
    @Query(value = """
            SELECT m.conversation_id AS "conversationId", COUNT(*) AS "unread"
            FROM chat_messages m
            LEFT JOIN conversation_reads r
                   ON r.conversation_id = m.conversation_id
                  AND r.user_id = :userId
            WHERE m.conversation_id IN (:conversationIds)
              AND m.sender_id <> :userId
              AND (r.last_read_at IS NULL OR m.created_at > r.last_read_at)
            GROUP BY m.conversation_id
            """, nativeQuery = true)
    List<ConversationUnreadCount> countUnreadByConversation(
            @Param("userId")
            UUID userId,

            @Param("conversationIds")
            Collection<UUID> conversationIds
    );


    /* Mesma regra, somada em todas as conversas do usuário (badge). */
    @Query(value = """
            SELECT COUNT(DISTINCT m.conversation_id) AS "conversations", COUNT(*) AS "messages"
            FROM chat_messages m
            JOIN conversation_participants p
              ON p.conversation_id = m.conversation_id
             AND p.user_id = :userId
            LEFT JOIN conversation_reads r
                   ON r.conversation_id = m.conversation_id
                  AND r.user_id = :userId
            WHERE m.sender_id <> :userId
              AND (r.last_read_at IS NULL OR m.created_at > r.last_read_at)
            """, nativeQuery = true)
    UnreadSummary countUnread(
            @Param("userId")
            UUID userId
    );
}
