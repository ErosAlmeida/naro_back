package com.naro.publication.domain.repository;

import com.naro.publication.domain.entity.Comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository
        extends JpaRepository<Comment, UUID> {


    /** Comentários diretos da publicação, mais recentes primeiro. */
    @EntityGraph(attributePaths = "author")
    Page<Comment>
    findByPublication_IdAndParentCommentIsNullOrderByCreatedAtDescIdDesc(
            UUID publicationId,
            Pageable pageable
    );


    /** Respostas diretas de um comentário, em ordem de conversa. */
    @EntityGraph(attributePaths = "author")
    Page<Comment>
    findByParentComment_IdOrderByCreatedAtAscIdAsc(
            UUID parentCommentId,
            Pageable pageable
    );


    /** Vale para qualquer nível: todo comentário guarda a publicação original. */
    Optional<Comment> findByIdAndPublication_Id(
            UUID id,
            UUID publicationId
    );


    /** Total de comentários da publicação, em todos os níveis. */
    @Query("""
            SELECT new com.naro.publication.domain.repository.IdCount(
                c.publication.id,
                COUNT(c)
            )
            FROM Comment c
            WHERE c.publication.id IN :publicationIds
            GROUP BY c.publication.id
            """)
    List<IdCount> countPerPublication(
            @Param("publicationIds")
            Collection<UUID> publicationIds
    );


    /** Respostas diretas de cada comentário da página. */
    @Query("""
            SELECT new com.naro.publication.domain.repository.IdCount(
                c.parentComment.id,
                COUNT(c)
            )
            FROM Comment c
            WHERE c.parentComment.id IN :commentIds
            GROUP BY c.parentComment.id
            """)
    List<IdCount> countRepliesPerComment(
            @Param("commentIds")
            Collection<UUID> commentIds
    );
}
