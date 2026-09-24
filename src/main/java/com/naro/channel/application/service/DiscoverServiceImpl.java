package com.naro.channel.application.service;

import com.naro.channel.application.dto.DiscoverChannelResponse;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.repository.ChannelFollowCount;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.channel.domain.repository.FollowRepository;
import com.naro.exception.BadRequestException;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DiscoverServiceImpl implements DiscoverService {

    private final ChannelService channelService;
    private final ChannelRepository channelRepository;
    private final FollowRepository followRepository;
    private final ObjectStorageService storageService;

    public DiscoverServiceImpl(
            ChannelService channelService,
            ChannelRepository channelRepository,
            FollowRepository followRepository,
            ObjectStorageService storageService
    ) {
        this.channelService = channelService;
        this.channelRepository = channelRepository;
        this.followRepository = followRepository;
        this.storageService = storageService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiscoverChannelResponse> discover(UUID activeChannelId, String search,
                                                   Pageable pageable, User currentUser) {
        channelService.getOwnedChannel(activeChannelId, currentUser);

        String normalized = search == null ? "" : search.trim();
        if (normalized.length() > 100) {
            throw new BadRequestException("Search query must have at most 100 characters");
        }

        Page<Channel> channels = normalized.isEmpty()
                ? channelRepository.findByOwner_IdNotOrderByCreatedAtDescIdDesc(currentUser.getId(), pageable)
                : channelRepository.searchDiscoverable(currentUser.getId(), containsPattern(normalized), pageable);

        List<UUID> ids = channels.getContent().stream().map(Channel::getId).toList();
        if (ids.isEmpty()) {
            return channels.map(channel -> toResponse(channel, 0, false));
        }

        Map<UUID, Long> followersCount = followRepository.countFollowersForChannels(ids).stream()
                .collect(Collectors.toMap(ChannelFollowCount::channelId, ChannelFollowCount::count));
        Set<UUID> followingIds = followRepository.findFollowingChannelIds(activeChannelId, ids);

        return channels.map(channel -> toResponse(
                channel,
                followersCount.getOrDefault(channel.getId(), 0L),
                followingIds.contains(channel.getId())
        ));
    }

    private DiscoverChannelResponse toResponse(Channel channel, long followersCount, boolean following) {
        return new DiscoverChannelResponse(
                channel.getId(),
                channel.getName(),
                channel.getSlug(),
                channel.getDescription(),
                channel.getProfileImageKey() == null ? null : storageService.createReadUrl(channel.getProfileImageKey()),
                followersCount,
                following
        );
    }

    private static String containsPattern(String search) {
        String escaped = search.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
