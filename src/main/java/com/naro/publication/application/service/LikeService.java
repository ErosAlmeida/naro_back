package com.naro.publication.application.service;

import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Likes em publicações e em comentários (de qualquer nível).
 *
 * As regras são as mesmas para os dois:
 *   - o like é sempre do usuário autenticado
 *   - curtir de novo não cria um segundo like
 *   - descurtir remove apenas o like do próprio usuário
 */
public interface LikeService {

    /* Publicação */

    LikeSummaryResponse likePublication(
            UUID publicationId,
            User currentUser
    );


    void unlikePublication(
            UUID publicationId,
            User currentUser
    );


    LikeSummaryResponse getPublicationLikeSummary(
            UUID publicationId,
            User currentUser
    );


    Page<LikeResponse> getPublicationLikes(
            UUID publicationId,
            Pageable pageable
    );


    /** Resumo de cada publicação de uma página, em duas queries. */
    Map<UUID, LikeSummaryResponse> getPublicationLikeSummaries(
            Collection<UUID> publicationIds,
            User viewer
    );


    /* Comentário */

    LikeSummaryResponse likeComment(
            UUID commentId,
            User currentUser
    );


    void unlikeComment(
            UUID commentId,
            User currentUser
    );


    LikeSummaryResponse getCommentLikeSummary(
            UUID commentId,
            User currentUser
    );


    Page<LikeResponse> getCommentLikes(
            UUID commentId,
            Pageable pageable
    );


    /** Resumo de cada comentário de uma página, em duas queries. */
    Map<UUID, LikeSummaryResponse> getCommentLikeSummaries(
            Collection<UUID> commentIds,
            User viewer
    );
}
