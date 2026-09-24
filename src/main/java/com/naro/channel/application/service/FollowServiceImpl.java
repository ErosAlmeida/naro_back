package com.naro.channel.application.service;

import com.naro.channel.application.dto.FollowChannelResponse;
import com.naro.channel.application.dto.FollowResponse;
import com.naro.channel.application.dto.FollowingStatusResponse;
import com.naro.channel.domain.entity.Channel;
import com.naro.channel.domain.entity.Follow;
import com.naro.channel.domain.repository.ChannelRepository;
import com.naro.channel.domain.repository.FollowRepository;
import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.NotFoundException;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FollowServiceImpl implements FollowService {

    private final FollowRepository followRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final ObjectStorageService storageService;

    public FollowServiceImpl(
            FollowRepository followRepository,
            ChannelRepository channelRepository,
            ChannelService channelService,
            ObjectStorageService storageService
    ) {
        this.followRepository = followRepository;
        this.channelRepository = channelRepository;
        this.channelService = channelService;
        this.storageService = storageService;
    }

    @Override
    @Transactional
    public FollowResponse follow(UUID followerChannelId, UUID followingChannelId, User currentUser) {
        Channel follower = channelService.getOwnedChannel(followerChannelId, currentUser);
        if (followerChannelId.equals(followingChannelId)) {
            throw new BadRequestException("A channel cannot follow itself");
        }
        Channel following = findChannel(followingChannelId);
        if (followRepository.existsByFollowerChannel_IdAndFollowingChannel_Id(followerChannelId, followingChannelId)) {
            throw new ConflictException("Channel is already followed");
        }

        try {
            return toResponse(followRepository.saveAndFlush(Follow.between(follower, following)));
        } catch (DataIntegrityViolationException exception) {
            // A restrição UNIQUE também cobre duas requisições simultâneas.
            throw new ConflictException("Channel is already followed");
        }
    }

    @Override
    @Transactional
    public void unfollow(UUID followerChannelId, UUID followingChannelId, User currentUser) {
        channelService.getOwnedChannel(followerChannelId, currentUser);
        Follow follow = findFollow(followerChannelId, followingChannelId);
        followRepository.delete(follow);
    }

    @Override
    @Transactional(readOnly = true)
    public FollowingStatusResponse isFollowing(UUID followerChannelId, UUID followingChannelId, User currentUser) {
        channelService.getOwnedChannel(followerChannelId, currentUser);
        findChannel(followingChannelId);
        return followRepository.findByFollowerChannel_IdAndFollowingChannel_Id(followerChannelId, followingChannelId)
                .map(follow -> new FollowingStatusResponse(true, follow.isFavorite()))
                .orElseGet(() -> new FollowingStatusResponse(false, false));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowChannelResponse> getFollowing(UUID channelId, Pageable pageable) {
        findChannel(channelId);
        return followRepository.findByFollowerChannel_IdOrderByCreatedAtDescIdDesc(channelId, pageable)
                .map(follow -> toChannelResponse(follow.getFollowingChannel()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FollowChannelResponse> getFollowers(UUID channelId, Pageable pageable) {
        findChannel(channelId);
        return followRepository.findByFollowingChannel_IdOrderByCreatedAtDescIdDesc(channelId, pageable)
                .map(follow -> toChannelResponse(follow.getFollowerChannel()));
    }

    @Override
    @Transactional(readOnly = true)
    public long countFollowing(UUID channelId) {
        findChannel(channelId);
        return followRepository.countByFollowerChannel_Id(channelId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countFollowers(UUID channelId) {
        findChannel(channelId);
        return followRepository.countByFollowingChannel_Id(channelId);
    }

    @Override
    @Transactional
    public FollowResponse setFavorite(UUID followerChannelId, UUID followingChannelId,
                                      boolean favorite, User currentUser) {
        channelService.getOwnedChannel(followerChannelId, currentUser);
        Follow follow = findFollow(followerChannelId, followingChannelId);
        follow.setFavorite(favorite);
        return toResponse(follow);
    }

    private Channel findChannel(UUID channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new NotFoundException("Channel not found"));
    }

    private Follow findFollow(UUID followerChannelId, UUID followingChannelId) {
        return followRepository.findByFollowerChannel_IdAndFollowingChannel_Id(followerChannelId, followingChannelId)
                .orElseThrow(() -> new NotFoundException("Follow not found"));
    }

    private FollowChannelResponse toChannelResponse(Channel channel) {
        return new FollowChannelResponse(
                channel.getId(),
                channel.getName(),
                channel.getSlug(),
                channel.getDescription(),
                channel.getProfileImageKey() == null ? null : storageService.createReadUrl(channel.getProfileImageKey())
        );
    }

    private static FollowResponse toResponse(Follow follow) {
        return new FollowResponse(
                follow.getId(),
                follow.getFollowerChannel().getId(),
                follow.getFollowingChannel().getId(),
                follow.isFavorite(),
                follow.getInteractionCount(),
                follow.getLastInteractionAt(),
                follow.getRelevanceScore(),
                follow.getCreatedAt()
        );
    }
}
