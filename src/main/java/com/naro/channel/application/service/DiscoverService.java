package com.naro.channel.application.service;

import com.naro.channel.application.dto.DiscoverChannelResponse;
import com.naro.user.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DiscoverService {

    Page<DiscoverChannelResponse> discover(UUID activeChannelId, String search, Pageable pageable, User currentUser);
}
