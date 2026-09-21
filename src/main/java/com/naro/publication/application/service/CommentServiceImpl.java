package com.naro.publication.application.service;

import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.mapper.PublicationMapper;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;

    private final PublicationRepository publicationRepository;

    private final ChannelRepository channelRepository;

    private final LikeService likeService;

    private final PublicationMapper publicationMapper;


    public CommentServiceImpl(
            CommentRepository commentRepository,
            PublicationRepository publicationRepository,
            ChannelRepository channelRepository,
            LikeService likeService,
            PublicationMapper publicationMapper
    ) {
        this.commentRepository = commentRepository;
        this.publicationRepository = publicationRepository;
        this.channelRepository = channelRepository;
        this.likeService = likeService;
        this.publicationMapper = publicationMapper;
    }


    @Override
    @Transactional
    public CommentResponse commentOnPublication(
            UUID publicationId,
            CreateCommentRequest request,
            Channel author
    ) {

        Publication publication =
                publicationRepository
                        .findById(publicationId)
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Publication not found"
                                )
                        );

        Channel managedAuthor = channelRepository.getReferenceById(author.getId());
        Comment comment = Comment.onPublication(
                managedAuthor,
                publication,
                request.content().trim()
        );

        Comment saved = commentRepository.saveAndFlush(comment);

        return publicationMapper.toCommentResponse(
                saved,
                LikeSummaryResponse.none(),
                0
        );
    }


    /*
     * Igual para qualquer profundidade: o pai pode ser um comentário
     * raiz ou uma resposta de resposta. A publicação é herdada do pai.
     */
    @Override
    @Transactional
    public CommentResponse reply(
            UUID parentCommentId,
            CreateCommentRequest request,
            Channel author
    ) {

        Comment parent = commentRepository.findById(parentCommentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        Channel managedAuthor = channelRepository.getReferenceById(author.getId());
        Comment comment = Comment.reply(
                managedAuthor,
                parent,
                request.content().trim()
        );

        Comment saved = commentRepository.saveAndFlush(comment);

        return publicationMapper.toCommentResponse(
                saved,
                LikeSummaryResponse.none(),
                0
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getPublicationComments(
            UUID publicationId,
            Channel viewer,
            Pageable pageable
    ) {

        if (!publicationRepository.existsById(publicationId)) {

            throw new NotFoundException(
                    "Publication not found"
            );
        }

        Page<Comment> comments =
                commentRepository.findByPublication_IdAndParentCommentIsNullOrderByCreatedAtDescIdDesc(
                        publicationId,
                        pageable
                );

        return toResponses(comments, viewer);
    }


    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getReplies(
            UUID commentId,
            Channel viewer,
            Pageable pageable
    ) {

        if (!commentRepository.existsById(commentId)) {

            throw new NotFoundException(
                    "Comment not found"
            );
        }

        Page<Comment> comments =
                commentRepository.findByParentComment_IdOrderByCreatedAtAscIdAsc(
                        commentId,
                        pageable
                );

        return toResponses(comments, viewer);
    }


    /*
     * Funciona para qualquer nível, porque todo comentário guarda
     * a publicação original. As respostas (e os likes delas) são
     * removidas pelo ON DELETE CASCADE do banco.
     */
    @Override
    @Transactional
    public void delete(
            UUID publicationId,
            UUID commentId,
            Channel currentChannel
    ) {

        Comment comment =
                commentRepository
                        .findByIdAndPublication_Id(
                                commentId,
                                publicationId
                        )
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Comment not found"
                                )
                        );


        if (!comment.isAuthoredBy(currentChannel)) {

            throw new ForbiddenException(
                    "You can only delete your own comments"
            );
        }


        commentRepository.delete(comment);
    }


    /*
     * Três queries por página, qualquer que seja o tamanho:
     * likes, "curtido por mim" e quantidade de respostas diretas.
     */
    private Page<CommentResponse> toResponses(
            Page<Comment> comments,
            Channel viewer
    ) {

        List<UUID> commentIds =
                comments
                        .getContent()
                        .stream()
                        .map(Comment::getId)
                        .toList();

        if (commentIds.isEmpty()) {
            return comments.map(comment -> publicationMapper.toCommentResponse(comment, LikeSummaryResponse.none(), 0));
        }

        Map<UUID, LikeSummaryResponse> likes =
                likeService.getCommentLikeSummaries(commentIds, viewer);

        Map<UUID, Long> replyCounts =
                commentRepository
                        .countRepliesPerComment(commentIds)
                        .stream()
                        .collect(Collectors.toMap(IdCount::id, IdCount::count));

        return comments.map(comment ->
                publicationMapper.toCommentResponse(
                        comment,
                        likes.getOrDefault(comment.getId(), LikeSummaryResponse.none()),
                        replyCounts.getOrDefault(comment.getId(), 0L)
                )
        );
    }
}
