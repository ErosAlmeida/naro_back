package com.naro.encryption.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A Master Key do usuário cifrada para uma passkey.
 *
 * O browser deriva a KEK da saída WebAuthn PRF dessa passkey e cifra
 * a Master Key com AES-256-GCM. Cada passkey (Mac, Windows, celular)
 * tem seu registro, e todos protegem a mesma Master Key.
 *
 * O servidor recebe só o resultado cifrado: nunca a Master Key,
 * a KEK ou a saída do PRF.
 */
@Getter
@Entity
@Table(
        name = "user_encryption_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_encryption_keys_credential",
                columnNames = {"user_id", "credential_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEncryptionKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Id da credencial WebAuthn, em Base64URL sem padding. */
    @Column(name = "credential_id", nullable = false, updatable = false, length = 1400)
    private String credentialId;

    @Column(name = "wrapped_master_key", nullable = false, length = 128)
    private String wrappedMasterKey;

    @Column(nullable = false, length = 32)
    private String iv;

    @Column(nullable = false, length = 30)
    private String algorithm;

    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserEncryptionKey(
            User user,
            String credentialId,
            String wrappedMasterKey,
            String iv,
            String algorithm,
            int version
    ) {
        this.user = user;
        this.credentialId = credentialId;
        this.wrappedMasterKey = wrappedMasterKey;
        this.iv = iv;
        this.algorithm = algorithm;
        this.version = version;
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
