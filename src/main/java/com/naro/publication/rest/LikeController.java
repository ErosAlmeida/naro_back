package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.channel.application.service.ChannelService;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.service.LikeService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Likes em publicações e em comentários (de qualquer nível).
 * O like pertence ao canal de X-Channel-Id, validado contra o usuário autenticado.
 *
 *   POST   /publications/{publicationId}/likes            curtir (idempotente)
 *   DELETE /publications/{publicationId}/likes/me         descurtir (idempotente)
 *   GET    /publications/{publicationId}/likes/summary    contagem + "curtido por mim"
 *   GET    /publications/{publicationId}/likes            quem curtiu (paginado)
 *
 *   POST   /comments/{commentId}/likes
 *   DELETE /comments/{commentId}/likes/me
 *   GET    /comments/{commentId}/likes/summary
 *   GET    /comments/{commentId}/likes
 */
@Tag(name = "Likes", description = "Likes em publicações e comentários")
@RestController
@RequestMapping("/api/v1")
public class LikeController {

    private final LikeService likeService;
    private final ChannelService channelService;
    private final LikeModelAssembler likeAssembler;


    public LikeController(
            LikeService likeService,
            ChannelService channelService,
            LikeModelAssembler likeAssembler
    ) {
        this.likeService = likeService;
        this.channelService = channelService;
        this.likeAssembler = likeAssembler;
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @PostMapping("/publications/{publicationId}/likes")
    @Operation(summary = "Curte uma publicação (idempotente)")
    public LikeSummaryResponse likePublication(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        return likeService.likePublication(
                publicationId,
                channelService.getOwnedChannel(channelId, currentUser)
        );
    }


    @DeleteMapping("/publications/{publicationId}/likes/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Descurte uma publicação (idempotente)")
    public void unlikePublication(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        likeService.unlikePublication(
                publicationId,
                channelService.getOwnedChannel(channelId, currentUser)
        );

    }


    @GetMapping("/publications/{publicationId}/likes/summary")
    @Operation(summary = "Contagem de likes de uma publicação e se o canal selecionado curtiu")
    public LikeSummaryResponse getPublicationLikeSummary(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User currentUser,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId

    ) {

        return likeService.getPublicationLikeSummary(
                publicationId,
                channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser)
        );
    }


    @GetMapping("/publications/{publicationId}/likes")
    @Operation(summary = "Lista quem curtiu uma publicação, mais recentes primeiro")
    public PagedModel<EntityModel<LikeResponse>> getPublicationLikes(

            @PathVariable
            UUID publicationId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size,

            PagedResourcesAssembler<LikeResponse> pagedAssembler

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return pagedAssembler.toModel(
                likeService.getPublicationLikes(
                        publicationId,
                        pageable
                ),
                likeAssembler
        );
    }


    /* ---------------------------------------------------------------
     * Comentário
     * --------------------------------------------------------------- */

    @PostMapping("/comments/{commentId}/likes")
    @Operation(summary = "Curte um comentário (idempotente)")
    public LikeSummaryResponse likeComment(

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        return likeService.likeComment(
                commentId,
                channelService.getOwnedChannel(channelId, currentUser)
        );
    }


    @DeleteMapping("/comments/{commentId}/likes/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Descurte um comentário (idempotente)")
    public void unlikeComment(

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        likeService.unlikeComment(
                commentId,
                channelService.getOwnedChannel(channelId, currentUser)
        );

    }


    @GetMapping("/comments/{commentId}/likes/summary")
    @Operation(summary = "Contagem de likes de um comentário e se o canal selecionado curtiu")
    public LikeSummaryResponse getCommentLikeSummary(

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId

    ) {

        return likeService.getCommentLikeSummary(
                commentId,
                channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser)
        );
    }


    @GetMapping("/comments/{commentId}/likes")
    @Operation(summary = "Lista quem curtiu um comentário, mais recentes primeiro")
    public PagedModel<EntityModel<LikeResponse>> getCommentLikes(

            @PathVariable
            UUID commentId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size,

            PagedResourcesAssembler<LikeResponse> pagedAssembler

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return pagedAssembler.toModel(
                likeService.getCommentLikes(
                        commentId,
                        pageable
                ),
                likeAssembler
        );
    }
}
