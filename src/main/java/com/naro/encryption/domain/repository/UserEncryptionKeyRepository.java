package com.naro.encryption.domain.repository;

import com.naro.encryption.domain.entity.UserEncryptionKey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Toda consulta é filtrada pelo dono: não existe busca de chave
 * só pelo credentialId, então ninguém lê ou revoga a chave de outro.
 */
public interface UserEncryptionKeyRepository
        extends JpaRepository<UserEncryptionKey, UUID> {

    List<UserEncryptionKey> findAllByUser_IdOrderByCreatedAtAsc(UUID userId);

    Optional<UserEncryptionKey> findByUser_IdAndCredentialId(UUID userId, String credentialId);

    boolean existsByUser_IdAndCredentialId(UUID userId, String credentialId);

    long countByUser_Id(UUID userId);
}
