package com.naro.channel.application.service;

import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelFollowCount;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.channel.domain.repository.FollowRepository;
import com.naro.exception.BadRequestException;
import com.naro.exception.ForbiddenException;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Set;

import static com.naro.support.Fixtures.channel;
import static com.naro.support.Fixtures.persisted;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoverServiceImplTest {

    @Mock private ChannelService channelService;
    @Mock private ChannelRepository channelRepository;
    @Mock private FollowRepository followRepository;
    @Mock private ObjectStorageService storageService;

    private DiscoverServiceImpl service;
    private final User owner = user("owner");
    private final Channel dev = persisted(channel(owner, "dev"));
    private final Channel games = persisted(channel(owner, "games"));
    private final Channel spring = channel("spring");
    private final Channel nintendo = channel("nintendo");
    private final PageRequest page = PageRequest.of(0, 20);

    @BeforeEach
    void setUp() {
        service = new DiscoverServiceImpl(channelService, channelRepository, followRepository, storageService);
    }

    @Test
    void switchingActiveChannelChangesFollowingStateWithoutChangingTheAvailableChannels() {
        List<Channel> externalChannels = List.of(spring, nintendo);
        List<java.util.UUID> ids = List.of(spring.getId(), nintendo.getId());
        when(channelRepository.findByOwner_IdNotOrderByCreatedAtDescIdDesc(owner.getId(), page))
                .thenReturn(new PageImpl<>(externalChannels, page, 2));
        when(followRepository.countFollowersForChannels(ids))
                .thenReturn(List.of(new ChannelFollowCount(spring.getId(), 42)));
        when(followRepository.findFollowingChannelIds(dev.getId(), ids)).thenReturn(Set.of(spring.getId()));
        when(followRepository.findFollowingChannelIds(games.getId(), ids)).thenReturn(Set.of(nintendo.getId()));

        var asDev = service.discover(dev.getId(), null, page, owner).getContent();
        var asGames = service.discover(games.getId(), null, page, owner).getContent();

        verify(channelService).getOwnedChannel(dev.getId(), owner);
        verify(channelService).getOwnedChannel(games.getId(), owner);
        assertThat(asDev).extracting(response -> response.id()).containsExactlyElementsOf(ids);
        assertThat(asDev).extracting(response -> response.following()).containsExactly(true, false);
        assertThat(asGames).extracting(response -> response.following()).containsExactly(false, true);
        assertThat(asDev).extracting(response -> response.followersCount()).containsExactly(42L, 0L);
    }

    @Test
    void searchUsesTrimmedEscapedTermAndExcludesTheAuthenticatedOwner() {
        when(channelRepository.searchDiscoverable(owner.getId(), "%java\\_\\%%", page))
                .thenReturn(new PageImpl<>(List.of(), page, 0));

        assertThat(service.discover(dev.getId(), "  Java_%  ", page, owner)).isEmpty();

        verify(channelService).getOwnedChannel(dev.getId(), owner);
        verify(channelRepository).searchDiscoverable(owner.getId(), "%java\\_\\%%", page);
        verifyNoInteractions(followRepository);
    }

    @Test
    void cannotDiscoverUsingSomebodyElsesActiveChannel() {
        User other = user("other");
        when(channelService.getOwnedChannel(dev.getId(), other))
                .thenThrow(new ForbiddenException("Not your channel"));

        assertThatThrownBy(() -> service.discover(dev.getId(), null, page, other))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(channelRepository, followRepository);
    }

    @Test
    void rejectsAnExcessivelyLongSearchBeforeQuerying() {
        assertThatThrownBy(() -> service.discover(dev.getId(), "x".repeat(101), page, owner))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(channelRepository, followRepository);
    }
}
