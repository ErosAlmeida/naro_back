package com.naro.publication.application.service;

import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.dto.PublicationEngagement;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.mapper.PublicationMapper;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.storage.service.ObjectStorageService;
import com.naro.channel.domain.entity.Channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PublicationServiceImpl implements PublicationService {

    private final PublicationRepository publicationRepository;
    private final LikeService likeService;
    private final CommentRepository commentRepository;
    private final PublicationMapper publicationMapper;
    private final ObjectStorageService storageService;

    public PublicationServiceImpl(
            PublicationRepository publicationRepository,
            LikeService likeService,
            CommentRepository commentRepository,
            PublicationMapper publicationMapper,
            ObjectStorageService storageService
    ) {
        this.publicationRepository = publicationRepository;
        this.likeService = likeService;
        this.commentRepository = commentRepository;
        this.publicationMapper = publicationMapper;
        this.storageService = storageService;
    }

    @Override
    @Transactional
    public PublicationResponse create(
            CreatePublicationRequest request,
            Channel author
    ) {

        String imageKey = storageService.upload(
                "publications",
                request.getImage()
        );

        try {

            Publication publication = new Publication(
                    author,
                    request.getContent(),
                    imageKey
            );

            Publication saved =
                    publicationRepository.saveAndFlush(publication);

            String imageUrl =
                    storageService.createReadUrl(
                            saved.getImageKey()
                    );

            return publicationMapper.toResponse(
                    saved,
                    imageUrl,
                    PublicationEngagement.none()
            );

        } catch (RuntimeException exception) {

            deleteImageSafely(imageKey);

            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PublicationResponse getById(
            UUID publicationId,
            Channel currentChannel
    ) {

        Publication publication =
                publicationRepository
                        .findWithAuthorById(publicationId)
                        .orElseThrow(
                                () -> new NotFoundException(
                                        "Publication not found"
                                )
                        );

        Map<UUID, PublicationEngagement> engagements =
                loadEngagements(
                        List.of(publication),
                        currentChannel
                );

        return toResponse(
                publication,
                engagements.get(publicationId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PublicationResponse> getPublications(
            UUID authorId,
            Channel currentChannel,
            Pageable pageable
    ) {

        Page<Publication> publications =
                authorId == null
                        ? publicationRepository
                                .findAllByOrderByCreatedAtDescIdDesc(pageable)
                        : publicationRepository
                                .findByAuthor_IdOrderByCreatedAtDescIdDesc(authorId, pageable);

        Map<UUID, PublicationEngagement> engagements =
                loadEngagements(
                        publications.getContent(),
                        currentChannel
                );

        return publications.map(
                publication -> toResponse(
                        publication,
                        engagements.get(publication.getId())
                )
        );
    }

    /*
     * Três queries por página, independente do tamanho dela:
     * likes, "curtido por mim" e total de comentários em todos os níveis.
     */
    private Map<UUID, PublicationEngagement> loadEngagements(
            List<Publication> publications,
            Channel currentChannel
    ) {

        if (publications.isEmpty()) {
            return Map.of();
        }

        List<UUID> publicationIds =
                publications.stream().map(Publication::getId).toList();

        Map<UUID, LikeSummaryResponse> likes =
                likeService.getPublicationLikeSummaries(publicationIds, currentChannel);

        Map<UUID, Long> commentCounts =
                commentRepository
                        .countPerPublication(publicationIds)
                        .stream()
                        .collect(Collectors.toMap(IdCount::id, IdCount::count));

        return publications
                .stream()
                .collect(Collectors.toMap(
                        Publication::getId,
                        publication -> {
                            LikeSummaryResponse publicationLikes = likes.getOrDefault(publication.getId(), LikeSummaryResponse.none());

                            return new PublicationEngagement(
                                    publicationLikes.likeCount(),
                                    commentCounts.getOrDefault(publication.getId(), 0L),
                                    publicationLikes.likedByCurrentChannel()
                            );
                        },
                        (first, second) -> first
                ));
    }

    private PublicationResponse toResponse(
            Publication publication,
            PublicationEngagement engagement
    ) {

        String imageUrl =
                storageService.createReadUrl(
                        publication.getImageKey()
                );

        return publicationMapper.toResponse(
                publication,
                imageUrl,
                engagement
        );
    }

    private void deleteImageSafely(String imageKey) {

        try {
            storageService.delete(imageKey);
        } catch (RuntimeException ignored) {
            // adicionar log depois
        }
    }
}
