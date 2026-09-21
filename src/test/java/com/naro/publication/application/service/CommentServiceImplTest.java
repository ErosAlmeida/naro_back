package com.naro.publication.application.service;

import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.mapper.PublicationMapperImpl;
import com.naro.storage.service.ObjectReadUrls;
import com.naro.storage.service.ObjectStorageService;
import com.naro.publication.domain.entity.Comment;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.CommentRepository;
import com.naro.publication.domain.repository.IdCount;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.naro.support.Fixtures.comment;
import static com.naro.support.Fixtures.persisted;
import static com.naro.support.Fixtures.publication;
import static com.naro.support.Fixtures.reply;
import static com.naro.support.Fixtures.channel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private PublicationRepository publicationRepository;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private LikeService likeService;

    private CommentServiceImpl commentService;

    private final Channel leonardo = channel("leonardo");

    private final Channel maria = channel("maria");

    private final Publication publication = publication(maria);


    @Mock
    private ObjectStorageService storageService;


    @BeforeEach
    void setUp() {
        commentService = new CommentServiceImpl(
                commentRepository,
                publicationRepository,
                channelRepository,
                likeService,
                new PublicationMapperImpl(new ObjectReadUrls(storageService))
        );
    }


    @Test
    void commentOnPublicationCreatesTopLevelCommentAuthoredByAuthenticatedUser() {

        when(publicationRepository.findById(publication.getId())).thenReturn(Optional.of(publication));
        authenticatedIs(leonardo);
        savingReturnsPersisted();

        CommentResponse response = commentService.commentOnPublication(
                publication.getId(),
                new CreateCommentRequest("  Muito boa!  "),
                leonardo
        );

        Comment saved = savedComment();
        assertThat(saved.getPublication()).isSameAs(publication);
        assertThat(saved.getParentComment()).isNull();
        assertThat(saved.getAuthor()).isSameAs(leonardo);
        assertThat(saved.getContent()).isEqualTo("Muito boa!");

        assertThat(response.parentCommentId()).isNull();
        assertThat(response.publicationId()).isEqualTo(publication.getId());
        assertThat(response.author().id()).isEqualTo(leonardo.getId());
        assertThat(response.author().slug()).isEqualTo("leonardo");
        assertThat(response.likeCount()).isZero();
        assertThat(response.replyCount()).isZero();
    }


    @Test
    void replyToCommentKeepsThePublicationOfTheParent() {

        Comment parent = comment(maria, publication, "A");

        when(commentRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        authenticatedIs(leonardo);
        savingReturnsPersisted();

        CommentResponse response = commentService.reply(parent.getId(), new CreateCommentRequest("Discordo"), leonardo);

        Comment saved = savedComment();
        assertThat(saved.getParentComment()).isSameAs(parent);
        assertThat(saved.getPublication()).isSameAs(publication);
        assertThat(response.parentCommentId()).isEqualTo(parent.getId());
        assertThat(response.publicationId()).isEqualTo(publication.getId());
    }


    @Test
    void replyToReplyWorksTheSameAtAnyDepth() {

        Comment a = comment(maria, publication, "A");
        Comment b = reply(leonardo, a, "B");
        Comment c = reply(maria, b, "C");

        when(commentRepository.findById(c.getId())).thenReturn(Optional.of(c));
        authenticatedIs(leonardo);
        savingReturnsPersisted();

        CommentResponse response = commentService.reply(c.getId(), new CreateCommentRequest("D"), leonardo);

        Comment saved = savedComment();
        assertThat(saved.getParentComment()).isSameAs(c);
        assertThat(saved.getPublication()).isSameAs(publication);
        assertThat(response.parentCommentId()).isEqualTo(c.getId());
    }


    @Test
    void replyToNonexistentCommentThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(commentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.reply(missing, new CreateCommentRequest("Oi"), leonardo))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Comment not found");

        verify(commentRepository, never()).saveAndFlush(any());
    }


    @Test
    void commentOnNonexistentPublicationThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(publicationRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.commentOnPublication(missing, new CreateCommentRequest("Oi"), leonardo))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Publication not found");

        verify(commentRepository, never()).saveAndFlush(any());
    }


    @Test
    void repliesPageComesWithLikesAndReplyCountsLoadedInBatch() {

        Comment parent = comment(maria, publication, "A");
        Comment liked = reply(leonardo, parent, "B");
        Comment quiet = reply(maria, parent, "C");
        List<UUID> ids = List.of(liked.getId(), quiet.getId());
        Pageable pageable = PageRequest.of(0, 20);

        when(commentRepository.existsById(parent.getId())).thenReturn(true);
        when(commentRepository.findByParentComment_IdOrderByCreatedAtAscIdAsc(parent.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(liked, quiet), pageable, 2));
        when(likeService.getCommentLikeSummaries(ids, leonardo))
                .thenReturn(Map.of(liked.getId(), new LikeSummaryResponse(8, true), quiet.getId(), LikeSummaryResponse.none()));
        when(commentRepository.countRepliesPerComment(ids))
                .thenReturn(List.of(new IdCount(liked.getId(), 3)));

        Page<CommentResponse> page = commentService.getReplies(parent.getId(), leonardo, pageable);

        assertThat(page.getContent())
                .extracting(CommentResponse::content, CommentResponse::likeCount, CommentResponse::likedByCurrentChannel, CommentResponse::replyCount)
                .containsExactly(
                        tuple("B", 8L, true, 3L),
                        tuple("C", 0L, false, 0L)
                );
    }


    @Test
    void repliesOfNonexistentCommentThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(commentRepository.existsById(missing)).thenReturn(false);

        assertThatThrownBy(() -> commentService.getReplies(missing, leonardo, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }


    @Test
    void authorDeletesOwnCommentAtAnyDepth() {

        Comment nested = reply(leonardo, comment(maria, publication, "A"), "B");

        when(commentRepository.findByIdAndPublication_Id(nested.getId(), publication.getId()))
                .thenReturn(Optional.of(nested));

        commentService.delete(publication.getId(), nested.getId(), leonardo);

        verify(commentRepository).delete(nested);
    }


    @Test
    void userCannotDeleteSomeoneElsesComment() {

        Comment mariasComment = comment(maria, publication, "da Maria");

        when(commentRepository.findByIdAndPublication_Id(mariasComment.getId(), publication.getId()))
                .thenReturn(Optional.of(mariasComment));

        assertThatThrownBy(() -> commentService.delete(publication.getId(), mariasComment.getId(), leonardo))
                .isInstanceOf(ForbiddenException.class);

        verify(commentRepository, never()).delete(any());
    }


    @Test
    void deletingNonexistentCommentThrowsNotFound() {

        UUID missing = UUID.randomUUID();
        when(commentRepository.findByIdAndPublication_Id(missing, publication.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.delete(publication.getId(), missing, leonardo))
                .isInstanceOf(NotFoundException.class);
    }


    private void authenticatedIs(Channel user) {
        when(channelRepository.getReferenceById(user.getId())).thenReturn(user);
    }


    private void savingReturnsPersisted() {
        when(commentRepository.saveAndFlush(any(Comment.class)))
                .thenAnswer(invocation -> persisted(invocation.<Comment>getArgument(0)));
    }


    private Comment savedComment() {

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).saveAndFlush(captor.capture());

        return captor.getValue();
    }
}
