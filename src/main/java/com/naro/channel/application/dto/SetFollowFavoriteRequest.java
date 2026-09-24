package com.naro.channel.application.dto;

import jakarta.validation.constraints.NotNull;

public record SetFollowFavoriteRequest(@NotNull Boolean favorite) {
}
