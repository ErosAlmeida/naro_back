package com.naro.channel.domain.repository;

import com.naro.channel.domain.entity.Channel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {
    Optional<Channel> findBySlug(String slug);

    /** Canais do usuário autenticado, do mais antigo para o mais novo. */
    List<Channel> findByOwner_IdOrderByCreatedAtAsc(UUID ownerId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);
}
