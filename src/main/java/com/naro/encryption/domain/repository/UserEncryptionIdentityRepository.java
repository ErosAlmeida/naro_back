package com.naro.encryption.domain.repository;

import com.naro.encryption.domain.entity.UserEncryptionIdentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserEncryptionIdentityRepository
        extends JpaRepository<UserEncryptionIdentity, UUID> {

    List<UserEncryptionIdentity> findAllByUserIdIn(Collection<UUID> userIds);
}
