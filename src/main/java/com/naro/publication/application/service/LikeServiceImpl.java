package com.naro.publication.application.service;

import com.naro.exception.ConflictException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.mapper.PublicationMapper;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Like;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.LikeRepository;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LikeServiceImpl implements LikeService {

    private final LikeRepository likeRepository;

    private final PublicationRepository publicationRepository;

    private final CommentRepository commentRepository;

    private final UserRepository userRepository;

    private final PublicationMapper publicationMapper;


    public LikeServiceImpl(
            LikeRepository likeRepository,
            PublicationRepository publicationRepository,
            CommentRepository commentRepository,
            UserRepository userRepository,
            PublicationMapper publicationMapper
    ) {
        this.likeRepository = likeRepository;
        this.publicationRepository = publicationRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.publicationMapper = publicationMapper;
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @Override
    @Transactional
    public LikeSummaryResponse likePublication(
            UUID publicationId,
            User currentUser
    ) {

        Publication publication =
                findPublication(publicationId);

        if (!likeRepository.existsByUserIdAndPublicationId(currentUser.getId(), publicationId)) {

            save(
                    Like.publication(
                            userReference(currentUser),
                            publication
                    )
            );
        }

        return getPublicationLikeSummary(
                publicationId,
                currentUser
        );
    }


    @Override
    @Transactional
    public void unlikePublication(
            UUID publicationId,
            User currentUser
    ) {

        findPublication(publicationId);

        likeRepository.deleteByUserIdAndPublicationId(
                currentUser.getId(),
                publicationId
        );
    }


    @Override
    @Transactional(readOnly = true)
    public LikeSummaryResponse getPublicationLikeSummary(
            UUID publicationId,
            User currentUser
    ) {

        findPublication(publicationId);

        return new LikeSummaryResponse(
                likeRepository.countByPublicationId(publicationId),
                likeRepository.existsByUserIdAndPublicationId(currentUser.getId(), publicationId)
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<LikeResponse> getPublicationLikes(
            UUID publicationId,
            Pageable pageable
    ) {

        findPublication(publicationId);

        return likeRepository
                .findByPublicationIdOrderByCreatedAtDesc(publicationId, pageable)
                .map(publicationMapper::toLikeResponse);
    }


    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LikeSummaryResponse> getPublicationLikeSummaries(
            Collection<UUID> publicationIds,
            User viewer
    ) {

        if (publicationIds.isEmpty()) {
            return Map.of();
        }

        return summaries(
                publicationIds,
                likeRepository.countPerPublication(publicationIds),
                likeRepository.findPublicationIdsLikedBy(viewer.getId(), publicationIds)
        );
    }


    /* ---------------------------------------------------------------
     * Comentário (qualquer nível: raiz, resposta, resposta de resposta...)
     * --------------------------------------------------------------- */

    @Override
    @Transactional
    public LikeSummaryResponse likeComment(
            UUID commentId,
            User currentUser
    ) {

        Comment comment =
                findComment(commentId);

        if (!likeRepository.existsByUserIdAndCommentId(currentUser.getId(), commentId)) {

            save(
                    Like.comment(
                            userReference(currentUser),
                            comment
                    )
            );
        }

        return getCommentLikeSummary(
                commentId,
                currentUser
        );
    }


    @Override
    @Transactional
    public void unlikeComment(
            UUID commentId,
            User currentUser
    ) {

        findComment(commentId);

        likeRepository.deleteByUserIdAndCommentId(
                currentUser.getId(),
                commentId
        );
    }


    @Override
    @Transactional(readOnly = true)
    public LikeSummaryResponse getCommentLikeSummary(
            UUID commentId,
            User currentUser
    ) {

        findComment(commentId);

        return new LikeSummaryResponse(
                likeRepository.countByCommentId(commentId),
                likeRepository.existsByUserIdAndCommentId(currentUser.getId(), commentId)
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<LikeResponse> getCommentLikes(
            UUID commentId,
            Pageable pageable
    ) {

        findComment(commentId);

        return likeRepository
                .findByCommentIdOrderByCreatedAtDesc(commentId, pageable)
                .map(publicationMapper::toLikeResponse);
    }


    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LikeSummaryResponse> getCommentLikeSummaries(
            Collection<UUID> commentIds,
            User viewer
    ) {

        if (commentIds.isEmpty()) {
            return Map.of();
        }

        return summaries(
                commentIds,
                likeRepository.countPerComment(commentIds),
                likeRepository.findCommentIdsLikedBy(viewer.getId(), commentIds)
        );
    }


    /* ---------------------------------------------------------------
     * Compartilhado
     * --------------------------------------------------------------- */

    /*
     * O exists antes do save evita o like duplicado no caso normal.
     * Se dois pedidos do mesmo usuário chegarem ao mesmo tempo, o
     * índice único do banco barra o segundo, que vira 409.
     */
    private void save(Like like) {

        try {

            likeRepository.saveAndFlush(like);

        } catch (DataIntegrityViolationException duplicate) {

            throw new ConflictException(
                    "Already liked"
            );
        }
    }


    /* Todo id recebido aparece no resultado, com zero likes se não tiver nenhum. */
    private static Map<UUID, LikeSummaryResponse> summaries(
            Collection<UUID> ids,
            List<IdCount> likeCounts,
            Set<UUID> likedByViewer
    ) {

        Map<UUID, Long> countById =
                likeCounts
                        .stream()
                        .collect(Collectors.toMap(IdCount::id, IdCount::count));

        return ids
                .stream()
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> new LikeSummaryResponse(
                                countById.getOrDefault(id, 0L),
                                likedByViewer.contains(id)
                        )
                ));
    }


    private Publication findPublication(UUID publicationId) {

        return publicationRepository
                .findById(publicationId)
                .orElseThrow(
                        () -> new NotFoundException(
                                "Publication not found"
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


    private User userReference(User user) {
        return userRepository.getReferenceById(user.getId());
    }
}
