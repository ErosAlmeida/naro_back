package com.naro.channel.domain.repository;

import com.naro.channel.domain.entity.Channel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, UUID> {
    Optional<Channel> findBySlug(String slug);

    /** Canais do usuário autenticado, do mais antigo para o mais novo. */
    List<Channel> findByOwner_IdOrderByCreatedAtAsc(UUID ownerId);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    Page<Channel> findByOwner_IdNotOrderByCreatedAtDescIdDesc(UUID ownerId, Pageable pageable);

    @Query("""
            SELECT c
            FROM Channel c
            WHERE c.owner.id <> :excludedOwnerId
              AND (
                    LOWER(c.name) LIKE :pattern ESCAPE '\\'
                    OR LOWER(c.slug) LIKE :pattern ESCAPE '\\'
              )
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    Page<Channel> searchDiscoverable(
            @Param("excludedOwnerId") UUID excludedOwnerId,
            @Param("pattern") String pattern,
            Pageable pageable
    );
}
