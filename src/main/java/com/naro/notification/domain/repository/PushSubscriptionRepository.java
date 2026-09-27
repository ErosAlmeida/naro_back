package com.naro.notification.domain.repository;

import com.naro.notification.domain.entity.PushSubscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository
        extends JpaRepository<PushSubscription, UUID> {


    /*
     * Cadastro idempotente e seguro entre abas/requisições simultâneas
     * (a constraint única do endpoint decide; ninguém recebe 500).
     *
     *   mesmo endpoint, mesma conta    atualiza chaves e aparelho, reativa
     *   mesmo endpoint, outra conta    o navegador mudou de dono: a linha
     *                                  vira uma inscrição nova (id, created_at
     *                                  e last_used_at novos) da conta atual
     *
     * No SET, s.* ainda são os valores antigos da linha.
     */
    @Modifying
    @Query(value = """
            INSERT INTO push_subscriptions AS s
                (id, user_id, endpoint, p256dh, auth, device_name, user_agent, enabled, created_at, updated_at)
            VALUES
                (:id, :userId, :endpoint, :p256dh, :auth, :deviceName, :userAgent, TRUE, :now, :now)
            ON CONFLICT (endpoint) DO UPDATE SET
                id           = CASE WHEN s.user_id = EXCLUDED.user_id THEN s.id ELSE EXCLUDED.id END,
                created_at   = CASE WHEN s.user_id = EXCLUDED.user_id THEN s.created_at ELSE EXCLUDED.created_at END,
                last_used_at = CASE WHEN s.user_id = EXCLUDED.user_id THEN s.last_used_at END,
                user_id      = EXCLUDED.user_id,
                p256dh       = EXCLUDED.p256dh,
                auth         = EXCLUDED.auth,
                device_name  = EXCLUDED.device_name,
                user_agent   = EXCLUDED.user_agent,
                enabled      = TRUE,
                updated_at   = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsert(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("endpoint") String endpoint,
            @Param("p256dh") String p256dh,
            @Param("auth") String auth,
            @Param("deviceName") String deviceName,
            @Param("userAgent") String userAgent,
            @Param("now") Instant now
    );


    Optional<PushSubscription> findByEndpoint(
            String endpoint
    );


    List<PushSubscription> findAllByUser_IdOrderByCreatedAtDesc(
            UUID userId
    );


    List<PushSubscription> findAllByUser_IdAndEnabledTrue(
            UUID userId
    );


    /* Só a conta dona apaga: id de outra conta simplesmente não encontra nada. */
    @Modifying
    @Query("""
            DELETE FROM PushSubscription s
            WHERE s.id = :id
              AND s.user.id = :userId
            """)
    int deleteOwned(
            @Param("id") UUID id,
            @Param("userId") UUID userId
    );


    /*
     * Mantém os aparelhos usados mais recentemente. Um cadastro atual tem
     * updated_at agora, então nunca é o removido.
     */
    @Modifying
    @Query(value = """
            DELETE FROM push_subscriptions
            WHERE id IN (
                SELECT id
                FROM push_subscriptions
                WHERE user_id = :userId
                ORDER BY GREATEST(last_used_at, updated_at) DESC, id
                OFFSET :limit
            )
            """, nativeQuery = true)
    int deleteBeyondLimit(
            @Param("userId") UUID userId,
            @Param("limit") int limit
    );


    /*
     * As três abaixo rodam na thread do push, fora de qualquer transação.
     * updatedAt é o valor lido antes do envio: se o navegador se cadastrou
     * de novo nesse meio tempo, a inscrição nova não é apagada nem desativada.
     */
    @Transactional
    @Modifying
    @Query("""
            DELETE FROM PushSubscription s
            WHERE s.id = :id
              AND s.updatedAt = :updatedAt
            """)
    int deleteIfUnchanged(
            @Param("id") UUID id,
            @Param("updatedAt") Instant updatedAt
    );


    @Transactional
    @Modifying
    @Query("""
            UPDATE PushSubscription s
            SET s.enabled = false
            WHERE s.id = :id
              AND s.updatedAt = :updatedAt
            """)
    int disableIfUnchanged(
            @Param("id") UUID id,
            @Param("updatedAt") Instant updatedAt
    );


    /* No máximo uma escrita por aparelho por hora, mesmo com muitas mensagens. */
    @Transactional
    @Modifying
    @Query("""
            UPDATE PushSubscription s
            SET s.lastUsedAt = :now
            WHERE s.id IN :ids
              AND (s.lastUsedAt IS NULL OR s.lastUsedAt < :staleBefore)
            """)
    int touchLastUsed(
            @Param("ids") Collection<UUID> ids,
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore
    );
}
