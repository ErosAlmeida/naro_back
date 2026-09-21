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
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;

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

    private final ChannelRepository channelRepository;

    private final PublicationMapper publicationMapper;


    public LikeServiceImpl(
            LikeRepository likeRepository,
            PublicationRepository publicationRepository,
            CommentRepository commentRepository,
            ChannelRepository channelRepository,
            PublicationMapper publicationMapper
    ) {
        this.likeRepository = likeRepository;
        this.publicationRepository = publicationRepository;
        this.commentRepository = commentRepository;
        this.channelRepository = channelRepository;
        this.publicationMapper = publicationMapper;
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @Override
    @Transactional
    public LikeSummaryResponse likePublication(
            UUID publicationId,
            Channel currentChannel
    ) {

        Publication publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new NotFoundException("Publication not found"));

        if (!likeRepository.existsByChannelIdAndPublicationId(currentChannel.getId(), publicationId)) {

            Channel channel = channelRepository.getReferenceById(currentChannel.getId());
            Like like = Like.publication(channel, publication);

            try {
                likeRepository.saveAndFlush(like);
            } catch (DataIntegrityViolationException duplicate) {
                throw new ConflictException("Already liked");
            }
        }

        return getPublicationLikeSummary(
                publicationId,
                currentChannel
        );
    }


    @Override
    @Transactional
    public void unlikePublication(
            UUID publicationId,
            Channel currentChannel
    ) {

        publicationRepository.findById(publicationId)
                .orElseThrow(() -> new NotFoundException("Publication not found"));

        likeRepository.deleteByChannelIdAndPublicationId(
                currentChannel.getId(),
                publicationId
        );
    }


    @Override
    @Transactional(readOnly = true)
    public LikeSummaryResponse getPublicationLikeSummary(
            UUID publicationId,
            Channel currentChannel
    ) {

        publicationRepository.findById(publicationId)
                .orElseThrow(() -> new NotFoundException("Publication not found"));

        return new LikeSummaryResponse(
                likeRepository.countByPublicationId(publicationId),
                currentChannel != null && likeRepository.existsByChannelIdAndPublicationId(currentChannel.getId(), publicationId)
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<LikeResponse> getPublicationLikes(
            UUID publicationId,
            Pageable pageable
    ) {

        publicationRepository.findById(publicationId)
                .orElseThrow(() -> new NotFoundException("Publication not found"));

        return likeRepository
                .findByPublicationIdOrderByCreatedAtDesc(publicationId, pageable)
                .map(publicationMapper::toLikeResponse);
    }


    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LikeSummaryResponse> getPublicationLikeSummaries(
            Collection<UUID> publicationIds,
            Channel viewer
    ) {

        if (publicationIds.isEmpty()) {
            return Map.of();
        }

        List<IdCount> likeCounts = likeRepository.countPerPublication(publicationIds);
        Set<UUID> likedByViewer = viewer == null ? Set.of() : likeRepository.findPublicationIdsLikedBy(viewer.getId(), publicationIds);

        return summaries(publicationIds, likeCounts, likedByViewer);
    }


    /* ---------------------------------------------------------------
     * Comentário (qualquer nível: raiz, resposta, resposta de resposta...)
     * --------------------------------------------------------------- */

    @Override
    @Transactional
    public LikeSummaryResponse likeComment(
            UUID commentId,
            Channel currentChannel
    ) {

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        if (!likeRepository.existsByChannelIdAndCommentId(currentChannel.getId(), commentId)) {

            Channel channel = channelRepository.getReferenceById(currentChannel.getId());
            Like like = Like.comment(channel, comment);

            try {
                likeRepository.saveAndFlush(like);
            } catch (DataIntegrityViolationException duplicate) {
                throw new ConflictException("Already liked");
            }
        }

        return getCommentLikeSummary(
                commentId,
                currentChannel
        );
    }


    @Override
    @Transactional
    public void unlikeComment(
            UUID commentId,
            Channel currentChannel
    ) {

        commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        likeRepository.deleteByChannelIdAndCommentId(
                currentChannel.getId(),
                commentId
        );
    }


    @Override
    @Transactional(readOnly = true)
    public LikeSummaryResponse getCommentLikeSummary(
            UUID commentId,
            Channel currentChannel
    ) {

        commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        return new LikeSummaryResponse(
                likeRepository.countByCommentId(commentId),
                currentChannel != null && likeRepository.existsByChannelIdAndCommentId(currentChannel.getId(), commentId)
        );
    }


    @Override
    @Transactional(readOnly = true)
    public Page<LikeResponse> getCommentLikes(
            UUID commentId,
            Pageable pageable
    ) {

        commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));

        return likeRepository
                .findByCommentIdOrderByCreatedAtDesc(commentId, pageable)
                .map(publicationMapper::toLikeResponse);
    }


    @Override
    @Transactional(readOnly = true)
    public Map<UUID, LikeSummaryResponse> getCommentLikeSummaries(
            Collection<UUID> commentIds,
            Channel viewer
    ) {

        if (commentIds.isEmpty()) {
            return Map.of();
        }

        List<IdCount> likeCounts = likeRepository.countPerComment(commentIds);
        Set<UUID> likedByViewer = viewer == null ? Set.of() : likeRepository.findCommentIdsLikedBy(viewer.getId(), commentIds);

        return summaries(commentIds, likeCounts, likedByViewer);
    }


    /* ---------------------------------------------------------------
     * Compartilhado
     * --------------------------------------------------------------- */

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
}
