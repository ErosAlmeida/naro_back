package com.naro.user.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "users",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_users_keycloak_id",
                        columnNames = "keycloak_id"
                ),
                @UniqueConstraint(
                        name = "uk_users_username",
                        columnNames = "username"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Valor da claim "sub" do JWT do Keycloak.
     *
     * Esse é o vínculo entre o usuário do Keycloak
     * e o usuário da nossa aplicação.
     */
    @Column(
            name = "keycloak_id",
            nullable = false,
            updatable = false
    )
    private String keycloakId;

    @Column(
            nullable = false,
            length = 100
    )
    private String username;

    @Column(
            length = 255
    )
    private String email;

    @Column(
            name = "first_name",
            length = 100
    )
    private String firstName;

    @Column(
            name = "last_name",
            length = 100
    )
    private String lastName;

    @Column(
            length = 500
    )
    private String bio;

    @Column(
            name = "avatar_url",
            length = 1000
    )
    private String avatarUrl;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    /**
     * Atualizado somente quando a última conexão
     * WebSocket do usuário é encerrada.
     *
     * Escrito via UserRepository#updateLastSeenAt para
     * não alterar updatedAt (que representa o perfil).
     */
    @Column(
            name = "last_seen_at"
    )
    private Instant lastSeenAt;

    public User(
            String keycloakId,
            String username,
            String email,
            String firstName,
            String lastName
    ) {
        this.keycloakId = keycloakId;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public void updateProfile(
            String firstName,
            String lastName,
            String bio,
            String avatarUrl
    ) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.bio = bio;
        this.avatarUrl = avatarUrl;
    }

    @PrePersist
    private void prePersist() {
        Instant now = Instant.now();

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
