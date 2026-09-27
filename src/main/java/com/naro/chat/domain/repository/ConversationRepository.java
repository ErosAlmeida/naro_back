package com.naro.chat.domain.repository;

import com.naro.chat.domain.entity.Conversation;
import com.naro.chat.domain.enums.ConversationType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ConversationRepository
        extends JpaRepository<Conversation, UUID> {


    @EntityGraph(attributePaths = "participants")
    @Query("""
            SELECT DISTINCT c
            FROM Conversation c
            JOIN c.participants p1
            JOIN c.participants p2
            
            WHERE c.type = :type
              AND p1.id = :firstUserId
              AND p2.id = :secondUserId
            """)
    Optional<Conversation> findDirectConversation(
            @Param("type")
            ConversationType type,

            @Param("firstUserId")
            UUID firstUserId,

            @Param("secondUserId")
            UUID secondUserId
    );


    @EntityGraph(attributePaths = "participants")
    @Query("""
            SELECT DISTINCT c
            FROM Conversation c
            JOIN c.participants participant
            
            WHERE participant.id = :userId
            
            ORDER BY c.updatedAt DESC
            """)
    List<Conversation> findAllByParticipantId(
            @Param("userId")
            UUID userId
    );


    @EntityGraph(attributePaths = "participants")
    @Query("""
            SELECT c
            FROM Conversation c
            WHERE c.id = :conversationId
            """)
    Optional<Conversation> findByIdWithParticipants(
            @Param("conversationId")
            UUID conversationId
    );


    /*
     * Dentre userIds, quais compartilham ao menos
     * uma conversa com o usuário informado.
     */
    @Query("""
            SELECT DISTINCT other.id
            FROM Conversation c
            JOIN c.participants me
            JOIN c.participants other
            
            WHERE me.id = :userId
              AND other.id IN :userIds
            """)
    Set<UUID> findContactIdsAmong(
            @Param("userId")
            UUID userId,

            @Param("userIds")
            Collection<UUID> userIds
    );


    /*
     * keycloakId de todos que compartilham ao menos
     * uma conversa com o usuário (exceto ele mesmo).
     */
    @Query("""
            SELECT DISTINCT other.keycloakId
            FROM Conversation c
            JOIN c.participants me
            JOIN c.participants other
            
            WHERE me.id = :userId
              AND other.id <> :userId
            """)
    Set<String> findContactKeycloakIds(
            @Param("userId")
            UUID userId
    );


    /*
     * Marca a conversa como lida até readAt. Nunca volta no tempo
     * (duas abas concorrentes ficam com a leitura mais recente).
     * A FK para conversation_participants recusa quem não participa.
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversation_reads (conversation_id, user_id, last_read_at)
            VALUES (:conversationId, :userId, :readAt)
            ON CONFLICT (conversation_id, user_id) DO UPDATE
                SET last_read_at = GREATEST(conversation_reads.last_read_at, EXCLUDED.last_read_at)
            """, nativeQuery = true)
    int markRead(
            @Param("conversationId")
            UUID conversationId,

            @Param("userId")
            UUID userId,

            @Param("readAt")
            Instant readAt
    );


    /* A mensagem criada em createdAt já foi vista pelo usuário? */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM conversation_reads r
                WHERE r.conversation_id = :conversationId
                  AND r.user_id = :userId
                  AND r.last_read_at >= :createdAt
            )
            """, nativeQuery = true)
    boolean isReadSince(
            @Param("conversationId")
            UUID conversationId,

            @Param("userId")
            UUID userId,

            @Param("createdAt")
            Instant createdAt
    );
}
