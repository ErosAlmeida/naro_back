package com.naro.channel.rest;

import com.naro.channel.application.dto.DiscoverChannelResponse;
import com.naro.channel.application.service.DiscoverService;
import com.naro.config.CurrentUser;
import com.naro.user.domain.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Channels", description = "Descoberta de canais")
@RestController
@RequestMapping("/api/v1/channels/discover")
public class DiscoverController {

    private final DiscoverService discoverService;
    private final DiscoverChannelModelAssembler channelAssembler;

    public DiscoverController(DiscoverService discoverService, DiscoverChannelModelAssembler channelAssembler) {
        this.discoverService = discoverService;
        this.channelAssembler = channelAssembler;
    }

    @GetMapping
    @Operation(summary = "Descobre canais de outros usuários, mais recentes primeiro")
    public PagedModel<EntityModel<DiscoverChannelResponse>> discover(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader("X-Channel-Id") UUID activeChannelId,
            @CurrentUser User currentUser,
            PagedResourcesAssembler<DiscoverChannelResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(
                discoverService.discover(
                        activeChannelId,
                        search,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)),
                        currentUser
                ),
                channelAssembler
        );
    }
}
