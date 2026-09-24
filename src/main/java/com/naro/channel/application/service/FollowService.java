package com.naro.channel.application.service;

import com.naro.channel.application.dto.FollowChannelResponse;
import com.naro.channel.application.dto.FollowResponse;
import com.naro.channel.application.dto.FollowingStatusResponse;
import com.naro.user.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FollowService {

    FollowResponse follow(UUID followerChannelId, UUID followingChannelId, User currentUser);

    void unfollow(UUID followerChannelId, UUID followingChannelId, User currentUser);

    FollowingStatusResponse isFollowing(UUID followerChannelId, UUID followingChannelId, User currentUser);

    Page<FollowChannelResponse> getFollowing(UUID channelId, Pageable pageable);

    Page<FollowChannelResponse> getFollowers(UUID channelId, Pageable pageable);

    long countFollowing(UUID channelId);

    long countFollowers(UUID channelId);

    FollowResponse setFavorite(UUID followerChannelId, UUID followingChannelId, boolean favorite, User currentUser);
}
