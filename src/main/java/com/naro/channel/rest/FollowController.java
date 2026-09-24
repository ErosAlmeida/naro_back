package com.naro.channel.rest;

import com.naro.channel.application.dto.FollowChannelResponse;
import com.naro.channel.application.dto.FollowCountResponse;
import com.naro.channel.application.dto.FollowResponse;
import com.naro.channel.application.dto.FollowingStatusResponse;
import com.naro.channel.application.dto.SetFollowFavoriteRequest;
import com.naro.channel.application.service.FollowService;
import com.naro.config.CurrentUser;
import com.naro.user.domain.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Follows", description = "Canais seguindo outros canais")
@RestController
@RequestMapping("/api/v1/channels/{channelId}")
public class FollowController {

    private final FollowService followService;
    private final FollowChannelModelAssembler channelAssembler;

    public FollowController(FollowService followService, FollowChannelModelAssembler channelAssembler) {
        this.followService = followService;
        this.channelAssembler = channelAssembler;
    }

    @PostMapping("/following/{targetChannelId}")
    @Operation(summary = "Segue outro canal usando um canal do usuário autenticado")
    public ResponseEntity<FollowResponse> follow(
            @PathVariable UUID channelId,
            @PathVariable UUID targetChannelId,
            @CurrentUser User currentUser
    ) {
        FollowResponse response = followService.follow(channelId, targetChannelId, currentUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(response);
    }

    @DeleteMapping("/following/{targetChannelId}")
    @Operation(summary = "Deixa de seguir outro canal")
    public ResponseEntity<Void> unfollow(
            @PathVariable UUID channelId,
            @PathVariable UUID targetChannelId,
            @CurrentUser User currentUser
    ) {
        followService.unfollow(channelId, targetChannelId, currentUser);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/following/{targetChannelId}/status")
    @Operation(summary = "Informa se o canal selecionado segue outro canal")
    public FollowingStatusResponse isFollowing(
            @PathVariable UUID channelId,
            @PathVariable UUID targetChannelId,
            @CurrentUser User currentUser
    ) {
        return followService.isFollowing(channelId, targetChannelId, currentUser);
    }

    @PatchMapping("/following/{targetChannelId}/favorite")
    @Operation(summary = "Marca ou desmarca como favorito um canal seguido")
    public FollowResponse setFavorite(
            @PathVariable UUID channelId,
            @PathVariable UUID targetChannelId,
            @Valid @RequestBody SetFollowFavoriteRequest request,
            @CurrentUser User currentUser
    ) {
        return followService.setFavorite(channelId, targetChannelId, request.favorite(), currentUser);
    }

    @GetMapping("/following")
    @Operation(summary = "Lista canais seguidos, mais recentes primeiro")
    public PagedModel<EntityModel<FollowChannelResponse>> getFollowing(
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            PagedResourcesAssembler<FollowChannelResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(
                followService.getFollowing(channelId, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))),
                channelAssembler
        );
    }

    @GetMapping("/followers")
    @Operation(summary = "Lista seguidores, mais recentes primeiro")
    public PagedModel<EntityModel<FollowChannelResponse>> getFollowers(
            @PathVariable UUID channelId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            PagedResourcesAssembler<FollowChannelResponse> pagedAssembler
    ) {
        return pagedAssembler.toModel(
                followService.getFollowers(channelId, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100))),
                channelAssembler
        );
    }

    @GetMapping("/following/count")
    @Operation(summary = "Conta canais seguidos")
    public FollowCountResponse countFollowing(@PathVariable UUID channelId) {
        return new FollowCountResponse(followService.countFollowing(channelId));
    }

    @GetMapping("/followers/count")
    @Operation(summary = "Conta seguidores")
    public FollowCountResponse countFollowers(@PathVariable UUID channelId) {
        return new FollowCountResponse(followService.countFollowers(channelId));
    }
}
