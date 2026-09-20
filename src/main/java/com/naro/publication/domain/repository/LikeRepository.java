package com.naro.publication.domain.repository;

import com.naro.publication.domain.entity.Like;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface LikeRepository
        extends JpaRepository<Like, UUID> {


    /* ---------------------------------------------------------------
     * Likes em publicações
     * --------------------------------------------------------------- */

    boolean existsByUserIdAndPublicationId(
            UUID userId,
            UUID publicationId
    );


    void deleteByUserIdAndPublicationId(
            UUID userId,
            UUID publicationId
    );


    long countByPublicationId(
            UUID publicationId
    );


    @EntityGraph(attributePaths = "user")
    Page<Like> findByPublicationIdOrderByCreatedAtDesc(
            UUID publicationId,
            Pageable pageable
    );


    /** Likes de cada publicação de uma página (uma query por página). */
    @Query("""
            SELECT new com.naro.publication.domain.repository.IdCount(
                l.publication.id,
                COUNT(l)
            )
            FROM Like l
            WHERE l.publication.id IN :publicationIds
            GROUP BY l.publication.id
            """)
    List<IdCount> countPerPublication(
            @Param("publicationIds")
            Collection<UUID> publicationIds
    );


    /** Quais publicações da página o usuário curtiu. */
    @Query("""
            SELECT l.publication.id
            FROM Like l
            WHERE l.user.id = :userId
              AND l.publication.id IN :publicationIds
            """)
    Set<UUID> findPublicationIdsLikedBy(
            @Param("userId")
            UUID userId,
            @Param("publicationIds")
            Collection<UUID> publicationIds
    );


    /* ---------------------------------------------------------------
     * Likes em comentários (de qualquer nível)
     * --------------------------------------------------------------- */

    boolean existsByUserIdAndCommentId(
            UUID userId,
            UUID commentId
    );


    void deleteByUserIdAndCommentId(
            UUID userId,
            UUID commentId
    );


    long countByCommentId(
            UUID commentId
    );


    @EntityGraph(attributePaths = "user")
    Page<Like> findByCommentIdOrderByCreatedAtDesc(
            UUID commentId,
            Pageable pageable
    );


    /** Likes de cada comentário de uma página (uma query por página). */
    @Query("""
            SELECT new com.naro.publication.domain.repository.IdCount(
                l.comment.id,
                COUNT(l)
            )
            FROM Like l
            WHERE l.comment.id IN :commentIds
            GROUP BY l.comment.id
            """)
    List<IdCount> countPerComment(
            @Param("commentIds")
            Collection<UUID> commentIds
    );


    /** Quais comentários da página o usuário curtiu. */
    @Query("""
            SELECT l.comment.id
            FROM Like l
            WHERE l.user.id = :userId
              AND l.comment.id IN :commentIds
            """)
    Set<UUID> findCommentIdsLikedBy(
            @Param("userId")
            UUID userId,
            @Param("commentIds")
            Collection<UUID> commentIds
    );
}
