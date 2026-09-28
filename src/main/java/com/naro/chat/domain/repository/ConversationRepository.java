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


    @EntityGraph(attributePaths = "participants")
    @Query("""
            SELECT DISTINCT c
            FROM Conversation c
            WHERE c.id IN :conversationIds
            """)
    List<Conversation> findAllByIdWithParticipants(
            @Param("conversationIds")
            Collection<UUID> conversationIds
    );


    /*
     * Marca a conversa como lida até readAt, e portanto também entregue.
     * Nunca volta no tempo (duas abas concorrentes ficam com a leitura mais
     * recente; GREATEST ignora NULL). A FK para conversation_participants
     * recusa quem não participa.
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversation_reads (conversation_id, user_id, last_read_at, last_delivered_at)
            VALUES (:conversationId, :userId, :readAt, :readAt)
            ON CONFLICT (conversation_id, user_id) DO UPDATE
                SET last_read_at = GREATEST(conversation_reads.last_read_at, EXCLUDED.last_read_at),
                    last_delivered_at = GREATEST(conversation_reads.last_delivered_at, EXCLUDED.last_delivered_at)
            """, nativeQuery = true)
    int markRead(
            @Param("conversationId")
            UUID conversationId,

            @Param("userId")
            UUID userId,

            @Param("readAt")
            Instant readAt
    );


    /*
     * Marca a conversa como entregue ao usuário até deliveredAt, sem mexer
     * na leitura. Retorna 0 quando o marcador já estava nesse ponto ou
     * adiante (nada mudou para quem enviou), 1 quando avançou.
     */
    @Modifying
    @Query(value = """
            INSERT INTO conversation_reads (conversation_id, user_id, last_delivered_at)
            VALUES (:conversationId, :userId, :deliveredAt)
            ON CONFLICT (conversation_id, user_id) DO UPDATE
                SET last_delivered_at = EXCLUDED.last_delivered_at
                WHERE conversation_reads.last_delivered_at IS NULL
                   OR conversation_reads.last_delivered_at < EXCLUDED.last_delivered_at
            """, nativeQuery = true)
    int markDelivered(
            @Param("conversationId")
            UUID conversationId,

            @Param("userId")
            UUID userId,

            @Param("deliveredAt")
            Instant deliveredAt
    );


    /*
     * Tiques de cada conversa do ponto de vista de userId: até onde TODOS os
     * outros participantes receberam e leram. Participante sem marcador (ou
     * com marcador NULL) deixa o valor NULL. Uma linha por conversa com
     * outros participantes.
     */
    @Query(value = """
            SELECT p.conversation_id AS "conversationId",
                   CASE WHEN COUNT(r.last_delivered_at) = COUNT(*) THEN MIN(r.last_delivered_at) END AS "deliveredAt",
                   CASE WHEN COUNT(r.last_read_at) = COUNT(*) THEN MIN(r.last_read_at) END AS "readAt"
            FROM conversation_participants p
            LEFT JOIN conversation_reads r
                   ON r.conversation_id = p.conversation_id
                  AND r.user_id = p.user_id
            WHERE p.conversation_id IN (:conversationIds)
              AND p.user_id <> :userId
            GROUP BY p.conversation_id
            """, nativeQuery = true)
    List<ConversationReceipts> findReceipts(
            @Param("conversationIds")
            Collection<UUID> conversationIds,

            @Param("userId")
            UUID userId
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
