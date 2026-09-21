package com.naro.publication.application.service;

import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.mapper.PublicationMapperImpl;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.storage.service.ObjectReadUrls;
import com.naro.storage.service.ObjectStorageService;
import com.naro.channel.domain.entity.Channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationServiceImplTest {

    @Mock
    private PublicationRepository publicationRepository;

    @Mock
    private LikeService likeService;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private ObjectStorageService storageService;

    private PublicationServiceImpl publicationService;

    private final Channel author = channel("leonardo");


    @BeforeEach
    void setUp() {
        publicationService = new PublicationServiceImpl(
                publicationRepository,
                likeService,
                commentRepository,
                new PublicationMapperImpl(new ObjectReadUrls(storageService)),
                storageService
        );
    }


    // ---------------------------------------------------------------
    // Criação (regressão do comportamento que já existia)
    // ---------------------------------------------------------------

    @Test
    void authorCarriesTheChannelPhotoAsAReadableUrl() {

        author.setProfileImageKey("channels/avatar.png");

        CreatePublicationRequest request = request();

        when(storageService.upload("publications", request.getImage()))
                .thenReturn("publications/key.png");
        when(publicationRepository.saveAndFlush(any(Publication.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(storageService.createReadUrl("publications/key.png"))
                .thenReturn("http://minio/publications/key.png");
        when(storageService.createReadUrl("channels/avatar.png"))
                .thenReturn("http://minio/channels/avatar.png?sig=1");

        PublicationResponse response = publicationService.create(request, author);

        assertThat(response.author().profileImageUrl()).isEqualTo("http://minio/channels/avatar.png?sig=1");
    }


    @Test
    void createUploadsImageAndSavesPublicationWithEmptyEngagement() {

        CreatePublicationRequest request = request();

        when(storageService.upload("publications", request.getImage()))
                .thenReturn("publications/key.png");
        when(publicationRepository.saveAndFlush(any(Publication.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(storageService.createReadUrl("publications/key.png"))
                .thenReturn("http://minio/publications/key.png");

        PublicationResponse response = publicationService.create(request, author);

        assertThat(response.id()).isNotNull();
        assertThat(response.author().id()).isEqualTo(author.getId());
        assertThat(response.author().name()).isEqualTo(author.getName());
        assertThat(response.author().slug()).isEqualTo("leonardo");
        // Canal sem foto: a autoria continua válida, só sem URL.
        assertThat(response.author().profileImageUrl()).isNull();
        assertThat(response.content()).isEqualTo("Minha publicação");
        assertThat(response.imageUrl()).isEqualTo("http://minio/publications/key.png");
        assertThat(response.likeCount()).isZero();
        assertThat(response.commentCount()).isZero();
        assertThat(response.likedByCurrentChannel()).isFalse();

        verifyNoInteractions(likeService, commentRepository);
    }


    @Test
    void createDeletesUploadedImageWhenSavingFails() {

        CreatePublicationRequest request = request();

        when(storageService.upload("publications", request.getImage()))
                .thenReturn("publications/key.png");
        when(publicationRepository.saveAndFlush(any(Publication.class)))
                .thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> publicationService.create(request, author))
                .isInstanceOf(IllegalStateException.class);

        verify(storageService).delete("publications/key.png");
    }


    // ---------------------------------------------------------------
    // Leitura
    // ---------------------------------------------------------------

    @Test
    void getByIdReturnsPublicationWithEngagement() {

        Channel viewer = channel("maria");
        Publication publication = publication(author);
        UUID id = publication.getId();

        when(publicationRepository.findWithAuthorById(id)).thenReturn(Optional.of(publication));
        when(likeService.getPublicationLikeSummaries(List.of(id), viewer))
                .thenReturn(Map.of(id, new LikeSummaryResponse(124, true)));
        when(commentRepository.countPerPublication(List.of(id)))
                .thenReturn(List.of(new IdCount(id, 17)));
        when(storageService.createReadUrl(anyString())).thenReturn("http://minio/image");

        PublicationResponse response = publicationService.getById(id, viewer);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.author().slug()).isEqualTo("leonardo");
        assertThat(response.imageUrl()).isEqualTo("http://minio/image");
        assertThat(response.likeCount()).isEqualTo(124);
        assertThat(response.commentCount()).isEqualTo(17);
        assertThat(response.likedByCurrentChannel()).isTrue();
    }


    @Test
    void getByIdOfNonexistentPublicationThrowsNotFound() {

        UUID id = UUID.randomUUID();

        when(publicationRepository.findWithAuthorById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> publicationService.getById(id, author))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Publication not found");

        verifyNoInteractions(likeService, commentRepository);
    }


    @Test
    void getPublicationsLoadsEngagementForWholePageInBatch() {

        Channel viewer = channel("maria");
        Pageable pageable = PageRequest.of(0, 20);
        Publication popular = publication(author);
        Publication quiet = publication(author);
        List<UUID> ids = List.of(popular.getId(), quiet.getId());

        when(publicationRepository.findAllByOrderByCreatedAtDescIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(popular, quiet), pageable, 2));
        when(likeService.getPublicationLikeSummaries(ids, viewer))
                .thenReturn(Map.of(popular.getId(), new LikeSummaryResponse(3, true), quiet.getId(), LikeSummaryResponse.none()));
        when(commentRepository.countPerPublication(ids))
                .thenReturn(List.of(new IdCount(popular.getId(), 2)));
        when(storageService.createReadUrl(anyString())).thenReturn("http://minio/image");

        Page<PublicationResponse> page =
                publicationService.getPublications(null, viewer, pageable);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(
                        PublicationResponse::id,
                        PublicationResponse::likeCount,
                        PublicationResponse::commentCount,
                        PublicationResponse::likedByCurrentChannel
                )
                .containsExactly(
                        tuple(popular.getId(), 3L, 2L, true),
                        tuple(quiet.getId(), 0L, 0L, false)
                );

        // Likes e comentários em lote: sem N+1.
        verify(likeService).getPublicationLikeSummaries(ids, viewer);
        verify(commentRepository).countPerPublication(ids);
    }


    @Test
    void getPublicationsFiltersByAuthorWhenProvided() {

        Pageable pageable = PageRequest.of(0, 20);

        when(publicationRepository.findByAuthor_IdOrderByCreatedAtDescIdDesc(author.getId(), pageable))
                .thenReturn(Page.empty(pageable));

        Page<PublicationResponse> page =
                publicationService.getPublications(author.getId(), author, pageable);

        assertThat(page.getContent()).isEmpty();
        verify(publicationRepository, never()).findAllByOrderByCreatedAtDescIdDesc(any());
    }


    @Test
    void getPublicationsWithEmptyPageSkipsEngagementQueries() {

        Pageable pageable = PageRequest.of(5, 20);

        when(publicationRepository.findAllByOrderByCreatedAtDescIdDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 40));

        Page<PublicationResponse> page =
                publicationService.getPublications(null, author, pageable);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(40);
        verifyNoInteractions(likeService, commentRepository);
    }


    private static CreatePublicationRequest request() {

        CreatePublicationRequest request = new CreatePublicationRequest();
        request.setContent("Minha publicação");
        request.setImage(new MockMultipartFile("image", "image.png", "image/png", new byte[]{1, 2, 3}));

        return request;
    }


    private static Publication publication(Channel author) {
        return withId(new Publication(author, "content", "publications/" + UUID.randomUUID() + ".png"));
    }


    private static Publication withId(Publication publication) {

        ReflectionTestUtils.setField(publication, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(publication, "createdAt", Instant.now());

        return publication;
    }


    private static Channel channel(String username) {
        return com.naro.support.Fixtures.channel(username);
    }
}
