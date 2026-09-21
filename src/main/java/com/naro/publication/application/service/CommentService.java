package com.naro.publication.application.service;

import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.channel.domain.entity.Channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Comentários em qualquer nível. Uma resposta é apenas um Comment
 * cujo pai é outro Comment; não há limite de profundidade.
 */
public interface CommentService {

    CommentResponse commentOnPublication(
            UUID publicationId,
            CreateCommentRequest request,
            Channel author
    );


    CommentResponse reply(
            UUID parentCommentId,
            CreateCommentRequest request,
            Channel author
    );


    /** Comentários diretos da publicação (sem as respostas). */
    Page<CommentResponse> getPublicationComments(
            UUID publicationId,
            Channel viewer,
            Pageable pageable
    );


    /** Respostas diretas de um comentário (um nível por vez). */
    Page<CommentResponse> getReplies(
            UUID commentId,
            Channel viewer,
            Pageable pageable
    );


    /** Apaga o comentário e toda a sua sub-árvore de respostas. */
    void delete(
            UUID publicationId,
            UUID commentId,
            Channel currentChannel
    );
}
