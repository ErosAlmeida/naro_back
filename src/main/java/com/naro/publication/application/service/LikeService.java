package com.naro.publication.application.service;

import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.channel.domain.entity.Channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Likes em publicações e em comentários (de qualquer nível).
 *
 * As regras são as mesmas para os dois:
 *   - o like é sempre do canal selecionado
 *   - curtir de novo não cria um segundo like
 *   - descurtir remove apenas o like do canal selecionado
 */
public interface LikeService {

    /* Publicação */

    LikeSummaryResponse likePublication(
            UUID publicationId,
            Channel currentChannel
    );


    void unlikePublication(
            UUID publicationId,
            Channel currentChannel
    );


    LikeSummaryResponse getPublicationLikeSummary(
            UUID publicationId,
            Channel currentChannel
    );


    Page<LikeResponse> getPublicationLikes(
            UUID publicationId,
            Pageable pageable
    );


    /** Resumo de cada publicação de uma página, em duas queries. */
    Map<UUID, LikeSummaryResponse> getPublicationLikeSummaries(
            Collection<UUID> publicationIds,
            Channel viewer
    );


    /* Comentário */

    LikeSummaryResponse likeComment(
            UUID commentId,
            Channel currentChannel
    );


    void unlikeComment(
            UUID commentId,
            Channel currentChannel
    );


    LikeSummaryResponse getCommentLikeSummary(
            UUID commentId,
            Channel currentChannel
    );


    Page<LikeResponse> getCommentLikes(
            UUID commentId,
            Pageable pageable
    );


    /** Resumo de cada comentário de uma página, em duas queries. */
    Map<UUID, LikeSummaryResponse> getCommentLikeSummaries(
            Collection<UUID> commentIds,
            Channel viewer
    );
}
