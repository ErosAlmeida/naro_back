package com.naro.encryption.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Par de chaves de identidade (ECDH P-256) de um usuário.
 *
 * A chave pública é distribuída aos contatos para derivar a chave de
 * cada conversa. A privada chega cifrada no browser com a Master Key
 * (AES-256-GCM): o servidor guarda, mas nunca consegue ler.
 *
 * Imutável: trocar a chave pública em silêncio permitiria a quem controla
 * o servidor se passar pelo usuário e deixaria o histórico ilegível.
 */
@Getter
@Entity
@Table(name = "user_encryption_identities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEncryptionIdentity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    /** Ponto P-256 não comprimido (65 bytes), em Base64. */
    @Column(name = "public_key", nullable = false, updatable = false, length = 128)
    private String publicKey;

    /** PKCS#8 da chave privada cifrado com a Master Key, em Base64. */
    @Column(name = "wrapped_private_key", nullable = false, updatable = false, length = 1024)
    private String wrappedPrivateKey;

    @Column(name = "private_key_iv", nullable = false, updatable = false, length = 32)
    private String privateKeyIv;

    /** SHA-256 da prova de posse da Master Key, em Base64. */
    @Column(name = "master_key_proof_hash", nullable = false, updatable = false, length = 64)
    private String masterKeyProofHash;

    @Column(nullable = false, updatable = false, length = 30)
    private String algorithm;

    @Column(nullable = false, updatable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public UserEncryptionIdentity(
            User user,
            String publicKey,
            String wrappedPrivateKey,
            String privateKeyIv,
            String masterKeyProofHash,
            String algorithm,
            int version
    ) {
        this.user = user;
        this.publicKey = publicKey;
        this.wrappedPrivateKey = wrappedPrivateKey;
        this.privateKeyIv = privateKeyIv;
        this.masterKeyProofHash = masterKeyProofHash;
        this.algorithm = algorithm;
        this.version = version;
    }

    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }
}
