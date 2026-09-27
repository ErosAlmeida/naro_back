package com.naro.notification.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Inscrição Web Push de um navegador/dispositivo (celular, notebook,
 * outro navegador...). Uma conta pode ter várias; um endpoint pertence a
 * uma única conta por vez.
 *
 * Criada e atualizada por PushSubscriptionRepository#upsert (concorrência
 * segura entre abas). Não guarda nenhum token da conta.
 */
@Getter
@Entity
@Table(name = "push_subscriptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushSubscription {

    @Id
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;


    /* URL do push service. É um segredo do navegador: nunca vai para logs. */
    @Column(
            nullable = false,
            length = 2048
    )
    private String endpoint;


    /* Chave pública ECDH P-256 do navegador (Base64url). */
    @Column(
            nullable = false,
            length = 128
    )
    private String p256dh;


    /* Segredo de autenticação do navegador (Base64url, 16 bytes). */
    @Column(
            nullable = false,
            length = 64
    )
    private String auth;


    @Column(
            name = "device_name",
            length = 100
    )
    private String deviceName;


    @Column(
            name = "user_agent",
            length = 512
    )
    private String userAgent;


    /* false: o push service recusou a inscrição; volta a true quando o navegador a cadastra de novo. */
    @Column(
            nullable = false
    )
    private boolean enabled;


    @Column(
            name = "created_at",
            nullable = false
    )
    private Instant createdAt;


    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;


    /* Último push aceito pelo push service (atualizado no máximo uma vez por hora). */
    @Column(
            name = "last_used_at"
    )
    private Instant lastUsedAt;
}
