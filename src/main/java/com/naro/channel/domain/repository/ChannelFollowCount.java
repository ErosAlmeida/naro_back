package com.naro.channel.domain.repository;

import java.util.UUID;

public record ChannelFollowCount(UUID channelId, long count) {
}
