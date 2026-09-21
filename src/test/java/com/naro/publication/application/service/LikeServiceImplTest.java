package com.naro.publication.application.service;

import com.naro.exception.ConflictException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.mapper.PublicationMapperImpl;
import com.naro.storage.service.ObjectReadUrls;
import com.naro.storage.service.ObjectStorageService;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Like;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.LikeRepository;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.naro.support.Fixtures.comment;
import static com.naro.support.Fixtures.publication;
import static com.naro.support.Fixtures.reply;
import static com.naro.support.Fixtures.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceImplTest {

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private PublicationRepository publicationRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private ChannelRepository channelRepository;

    private LikeServiceImpl likeService;

    private final Channel leonardo = channel("leonardo");

    private final Channel maria = channel("maria");

    private final Publication publication = publication(maria);

    private final Comment comment = comment(maria, publication, "A");


    @Mock
    private ObjectStorageService storageService;


    @BeforeEach
    void setUp() {
        likeService = new LikeServiceImpl(
                likeRepository,
                publicationRepository,
                commentRepository,
                channelRepository,
                new PublicationMapperImpl(new ObjectReadUrls(storageService))
        );
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @Test
    void likePublicationSavesALikeOnThePublicationByTheAuthenticatedUser() {

        publicationExists();
        authenticatedIs(leonardo);
        when(likeRepository.existsByChannelIdAndPublicationId(leonardo.getId(), publication.getId()))
                .thenReturn(false, true);
        when(likeRepository.countByPublicationId(publication.getId())).thenReturn(1L);

        LikeSummaryResponse summary = likeService.likePublication(publication.getId(), leonardo);

        Like saved = savedLike();
        assertThat(saved.getPublication()).isSameAs(publication);
        assertThat(saved.getComment()).isNull();
        assertThat(saved.getChannel()).isSameAs(leonardo);

        assertThat(summary).isEqualTo(new LikeSummaryResponse(1, true));
    }


    @Test
    void likingThePublicationAgainDoesNotSaveASecondLike() {

        publicationExists();
        when(likeRepository.existsByChannelIdAndPublicationId(leonardo.getId(), publication.getId())).thenReturn(true);
        when(likeRepository.countByPublicationId(publication.getId())).thenReturn(1L);

        LikeSummaryResponse summary = likeService.likePublication(publication.getId(), leonardo);

        verify(likeRepository, never()).saveAndFlush(any());
        assertThat(summary).isEqualTo(new LikeSummaryResponse(1, true));
    }


    @Test
    void concurrentDuplicateLikeOnPublicationIsRejectedByTheDatabaseAsConflict() {

        publicationExists();
        authenticatedIs(leonardo);
        when(likeRepository.existsByChannelIdAndPublicationId(leonardo.getId(), publication.getId())).thenReturn(false);
        when(likeRepository.saveAndFlush(any(Like.class)))
                .thenThrow(new DataIntegrityViolationException("uk_likes_publication_channel"));

        assertThatThrownBy(() -> likeService.likePublication(publication.getId(), leonardo))
                .isInstanceOf(ConflictException.class);
    }


    @Test
    void unlikePublicationRemovesOnlyTheAuthenticatedUsersLike() {

        publicationExists();

        likeService.unlikePublication(publication.getId(), leonardo);

        verify(likeRepository).deleteByChannelIdAndPublicationId(leonardo.getId(), publication.getId());
    }


    @Test
    void likeOnNonexistentPublicationThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(publicationRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> likeService.likePublication(missing, leonardo))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Publication not found");

        verify(likeRepository, never()).saveAndFlush(any());
    }


    @Test
    void publicationLikeSummariesForAPageUseOneCountAndOneLikedQuery() {

        Publication quiet = publication(maria);
        List<UUID> ids = List.of(publication.getId(), quiet.getId());

        when(likeRepository.countPerPublication(ids)).thenReturn(List.of(new IdCount(publication.getId(), 124)));
        when(likeRepository.findPublicationIdsLikedBy(leonardo.getId(), ids)).thenReturn(Set.of(publication.getId()));

        assertThat(likeService.getPublicationLikeSummaries(ids, leonardo)).containsExactlyInAnyOrderEntriesOf(Map.of(
                publication.getId(), new LikeSummaryResponse(124, true),
                quiet.getId(), LikeSummaryResponse.none()
        ));
    }


    /* ---------------------------------------------------------------
     * Comentário
     * --------------------------------------------------------------- */

    @Test
    void likeCommentSavesALikeOnTheCommentByTheAuthenticatedUser() {

        commentExists(comment);
        authenticatedIs(leonardo);
        when(likeRepository.existsByChannelIdAndCommentId(leonardo.getId(), comment.getId())).thenReturn(false, true);
        when(likeRepository.countByCommentId(comment.getId())).thenReturn(1L);

        LikeSummaryResponse summary = likeService.likeComment(comment.getId(), leonardo);

        Like saved = savedLike();
        assertThat(saved.getComment()).isSameAs(comment);
        assertThat(saved.getPublication()).isNull();
        assertThat(saved.getChannel()).isSameAs(leonardo);

        assertThat(summary).isEqualTo(new LikeSummaryResponse(1, true));
    }


    @Test
    void likeOnNestedReplyIsALikeOnThatReply() {

        Comment nested = reply(leonardo, reply(maria, comment, "B"), "C");

        commentExists(nested);
        authenticatedIs(maria);
        when(likeRepository.existsByChannelIdAndCommentId(maria.getId(), nested.getId())).thenReturn(false, true);
        when(likeRepository.countByCommentId(nested.getId())).thenReturn(1L);

        likeService.likeComment(nested.getId(), maria);

        assertThat(savedLike().getComment()).isSameAs(nested);
    }


    @Test
    void likingTheCommentAgainDoesNotSaveASecondLike() {

        commentExists(comment);
        when(likeRepository.existsByChannelIdAndCommentId(leonardo.getId(), comment.getId())).thenReturn(true);
        when(likeRepository.countByCommentId(comment.getId())).thenReturn(3L);

        LikeSummaryResponse summary = likeService.likeComment(comment.getId(), leonardo);

        verify(likeRepository, never()).saveAndFlush(any());
        assertThat(summary).isEqualTo(new LikeSummaryResponse(3, true));
    }


    @Test
    void unlikeCommentRemovesOnlyTheAuthenticatedUsersLike() {

        commentExists(comment);

        likeService.unlikeComment(comment.getId(), leonardo);

        verify(likeRepository).deleteByChannelIdAndCommentId(leonardo.getId(), comment.getId());
    }


    @Test
    void unlikeOnNonexistentCommentThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(commentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> likeService.unlikeComment(missing, leonardo))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Comment not found");

        verify(likeRepository, never()).deleteByChannelIdAndCommentId(any(), any());
    }


    @Test
    void commentSummaryTellsWhetherCurrentChannelLiked() {

        commentExists(comment);
        when(likeRepository.countByCommentId(comment.getId())).thenReturn(3L);
        when(likeRepository.existsByChannelIdAndCommentId(leonardo.getId(), comment.getId())).thenReturn(false);

        assertThat(likeService.getCommentLikeSummary(comment.getId(), leonardo))
                .isEqualTo(new LikeSummaryResponse(3, false));
    }


    @Test
    void commentLikeSummariesForAPageUseOneCountAndOneLikedQuery() {

        Comment quiet = comment(maria, publication, "sem likes");
        List<UUID> ids = List.of(comment.getId(), quiet.getId());

        when(likeRepository.countPerComment(ids)).thenReturn(List.of(new IdCount(comment.getId(), 20)));
        when(likeRepository.findCommentIdsLikedBy(leonardo.getId(), ids)).thenReturn(Set.of(comment.getId()));

        assertThat(likeService.getCommentLikeSummaries(ids, leonardo)).containsExactlyInAnyOrderEntriesOf(Map.of(
                comment.getId(), new LikeSummaryResponse(20, true),
                quiet.getId(), LikeSummaryResponse.none()
        ));
    }


    @Test
    void summariesForAnEmptyPageDoNotQuery() {

        assertThat(likeService.getCommentLikeSummaries(List.of(), leonardo)).isEmpty();
        assertThat(likeService.getPublicationLikeSummaries(List.of(), leonardo)).isEmpty();

        verifyNoInteractions(likeRepository);
    }


    private void publicationExists() {
        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));
    }


    private void commentExists(Comment comment) {
        when(commentRepository.findById(comment.getId())).thenReturn(Optional.of(comment));
    }


    private void authenticatedIs(Channel user) {
        when(channelRepository.getReferenceById(user.getId())).thenReturn(user);
    }


    private Like savedLike() {

        ArgumentCaptor<Like> captor = ArgumentCaptor.forClass(Like.class);
        verify(likeRepository).saveAndFlush(captor.capture());

        return captor.getValue();
    }
}
