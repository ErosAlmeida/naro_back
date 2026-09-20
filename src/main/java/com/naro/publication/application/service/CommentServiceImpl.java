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
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

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

    private final UserRepository userRepository;

    private final LikeService likeService;

    private final PublicationMapper publicationMapper;


    public CommentServiceImpl(
            CommentRepository commentRepository,
            PublicationRepository publicationRepository,
            UserRepository userRepository,
            LikeService likeService,
            PublicationMapper publicationMapper
    ) {
        this.commentRepository = commentRepository;
        this.publicationRepository = publicationRepository;
        this.userRepository = userRepository;
        this.likeService = likeService;
        this.publicationMapper = publicationMapper;
    }


    @Override
    @Transactional
    public CommentResponse commentOnPublication(
            UUID publicationId,
            CreateCommentRequest request,
            User author
    ) {

        Publication publication =
                publicationRepository
                        .findById(publicationId)
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Publication not found"
                                )
                        );

        return save(
                Comment.onPublication(
                        authorReference(author),
                        publication,
                        request.content().trim()
                )
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
            User author
    ) {

        Comment parent =
                findComment(parentCommentId);

        return save(
                Comment.reply(
                        authorReference(author),
                        parent,
                        request.content().trim()
                )
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getPublicationComments(
            UUID publicationId,
            User viewer,
            Pageable pageable
    ) {

        if (!publicationRepository.existsById(publicationId)) {

            throw new NotFoundException(
                    "Publication not found"
            );
        }

        return toResponses(
                commentRepository.findByPublication_IdAndParentCommentIsNullOrderByCreatedAtDescIdDesc(
                        publicationId,
                        pageable
                ),
                viewer
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<CommentResponse> getReplies(
            UUID commentId,
            User viewer,
            Pageable pageable
    ) {

        if (!commentRepository.existsById(commentId)) {

            throw new NotFoundException(
                    "Comment not found"
            );
        }

        return toResponses(
                commentRepository.findByParentComment_IdOrderByCreatedAtAscIdAsc(
                        commentId,
                        pageable
                ),
                viewer
        );
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
            User currentUser
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


        if (!comment.isAuthoredBy(currentUser)) {

            throw new ForbiddenException(
                    "You can only delete your own comments"
            );
        }


        commentRepository.delete(comment);
    }


    private CommentResponse save(Comment comment) {

        Comment saved =
                commentRepository.saveAndFlush(comment);

        return publicationMapper.toCommentResponse(
                saved,
                LikeSummaryResponse.none(),
                0
        );
    }


    /*
     * Três queries por página, qualquer que seja o tamanho:
     * likes, "curtido por mim" e quantidade de respostas diretas.
     */
    private Page<CommentResponse> toResponses(
            Page<Comment> comments,
            User viewer
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


    private Comment findComment(UUID commentId) {

        return commentRepository
                .findById(commentId)
                .orElseThrow(
                        () -> new NotFoundException(
                                "Comment not found"
                        )
                );
    }


    private User authorReference(User author) {
        return userRepository.getReferenceById(author.getId());
    }
}
