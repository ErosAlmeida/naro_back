package com.naro.channel.application.service;

import com.naro.channel.application.dto.FollowingStatusResponse;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.entity.Follow;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.channel.domain.repository.FollowRepository;
import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static com.naro.support.Fixtures.channel;
import static com.naro.support.Fixtures.persisted;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock private FollowRepository followRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private ChannelService channelService;
    @Mock private ObjectStorageService storageService;

    private FollowServiceImpl service;

    private final User owner = user("owner");
    private final Channel follower = persisted(channel(owner, "follower"));
    private final Channel target = channel("target");

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl(followRepository, channelRepository, channelService, storageService);
    }

    @Test
    void followSavesTheOwnedChannelPairAndStartsWithEmptySignals() {
        ownedFollower();
        when(channelRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(followRepository.saveAndFlush(any(Follow.class)))
                .thenAnswer(call -> persisted(call.getArgument(0)));

        var response = service.follow(follower.getId(), target.getId(), owner);

        ArgumentCaptor<Follow> saved = ArgumentCaptor.forClass(Follow.class);
        verify(followRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFollowerChannel()).isSameAs(follower);
        assertThat(saved.getValue().getFollowingChannel()).isSameAs(target);
        assertThat(response.followerChannelId()).isEqualTo(follower.getId());
        assertThat(response.followingChannelId()).isEqualTo(target.getId());
        assertThat(response.favorite()).isFalse();
        assertThat(response.interactionCount()).isZero();
        assertThat(response.lastInteractionAt()).isNull();
        assertThat(response.relevanceScore()).isNull();
    }

    @Test
    void ownChannelCannotFollowItself() {
        ownedFollower();

        assertThatThrownBy(() -> service.follow(follower.getId(), follower.getId(), owner))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(channelRepository, followRepository);
    }

    @Test
    void duplicateFollowIsRejectedBeforeSaving() {
        ownedFollower();
        when(channelRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(followRepository.existsByFollowerChannel_IdAndFollowingChannel_Id(follower.getId(), target.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.follow(follower.getId(), target.getId(), owner))
                .isInstanceOf(ConflictException.class);
        verify(followRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateFollowAlsoBecomesConflict() {
        ownedFollower();
        when(channelRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(followRepository.saveAndFlush(any(Follow.class)))
                .thenThrow(new DataIntegrityViolationException("uk_channel_follows_pair"));

        assertThatThrownBy(() -> service.follow(follower.getId(), target.getId(), owner))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void foreignChannelCannotBeUsedToFollowUnfollowOrMarkFavorite() {
        User other = user("other");
        when(channelService.getOwnedChannel(follower.getId(), other))
                .thenThrow(new ForbiddenException("Not your channel"));

        assertThatThrownBy(() -> service.follow(follower.getId(), target.getId(), other))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.unfollow(follower.getId(), target.getId(), other))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.setFavorite(follower.getId(), target.getId(), true, other))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(channelRepository, followRepository);
    }

    @Test
    void unfollowDeletesTheExactFollowAndMissingFollowReturnsNotFound() {
        ownedFollower();
        Follow existing = persisted(Follow.between(follower, target));
        when(followRepository.findByFollowerChannel_IdAndFollowingChannel_Id(follower.getId(), target.getId()))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty());

        service.unfollow(follower.getId(), target.getId(), owner);
        verify(followRepository).delete(existing);
        assertThatThrownBy(() -> service.unfollow(follower.getId(), target.getId(), owner))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void statusAndCountsUseTheRequestedChannelDirection() {
        ownedFollower();
        when(channelRepository.findById(target.getId())).thenReturn(Optional.of(target));
        Follow favorite = persisted(Follow.between(follower, target));
        favorite.setFavorite(true);
        when(followRepository.findByFollowerChannel_IdAndFollowingChannel_Id(follower.getId(), target.getId()))
                .thenReturn(Optional.of(favorite))
                .thenReturn(Optional.empty());
        when(channelRepository.findById(follower.getId())).thenReturn(Optional.of(follower));
        when(followRepository.countByFollowerChannel_Id(follower.getId())).thenReturn(3L);
        when(followRepository.countByFollowingChannel_Id(follower.getId())).thenReturn(5L);

        assertThat(service.isFollowing(follower.getId(), target.getId(), owner))
                .isEqualTo(new FollowingStatusResponse(true, true));
        assertThat(service.isFollowing(follower.getId(), target.getId(), owner))
                .isEqualTo(new FollowingStatusResponse(false, false));
        assertThat(service.countFollowing(follower.getId())).isEqualTo(3);
        assertThat(service.countFollowers(follower.getId())).isEqualTo(5);
    }

    @Test
    void listsFollowingAndFollowersFromOppositeEndsOfTheRelation() {
        when(channelRepository.findById(follower.getId())).thenReturn(Optional.of(follower));
        var page = PageRequest.of(0, 10);
        Follow following = persisted(Follow.between(follower, target));
        Follow followerOfMine = persisted(Follow.between(target, follower));
        when(followRepository.findByFollowerChannel_IdOrderByCreatedAtDescIdDesc(follower.getId(), page))
                .thenReturn(new PageImpl<>(List.of(following), page, 1));
        when(followRepository.findByFollowingChannel_IdOrderByCreatedAtDescIdDesc(follower.getId(), page))
                .thenReturn(new PageImpl<>(List.of(followerOfMine), page, 1));

        assertThat(service.getFollowing(follower.getId(), page).getContent())
                .singleElement().satisfies(response -> assertThat(response.id()).isEqualTo(target.getId()));
        assertThat(service.getFollowers(follower.getId(), page).getContent())
                .singleElement().satisfies(response -> assertThat(response.id()).isEqualTo(target.getId()));
    }

    @Test
    void favoriteUpdatesOnlyAnExistingOwnedFollow() {
        ownedFollower();
        Follow existing = persisted(Follow.between(follower, target));
        when(followRepository.findByFollowerChannel_IdAndFollowingChannel_Id(follower.getId(), target.getId()))
                .thenReturn(Optional.of(existing));

        var response = service.setFavorite(follower.getId(), target.getId(), true, owner);

        assertThat(existing.isFavorite()).isTrue();
        assertThat(response.favorite()).isTrue();
    }

    private void ownedFollower() {
        when(channelService.getOwnedChannel(follower.getId(), owner)).thenReturn(follower);
    }
}
