package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.channel.application.service.ChannelService;
import com.naro.publication.application.dto.LikeResponse;
import com.naro.publication.application.dto.LikeSummaryResponse;
import com.naro.publication.application.service.LikeService;
import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
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
@RestController
@RequestMapping("/api/v1")
public class LikeController {

    private final LikeService likeService;
    private final ChannelService channelService;


    public LikeController(
            LikeService likeService,
            ChannelService channelService
    ) {
        this.likeService = likeService;
        this.channelService = channelService;
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @PostMapping("/publications/{publicationId}/likes")
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
    public ResponseEntity<Void> unlikePublication(

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


        return ResponseEntity
                .noContent()
                .build();
    }


    @GetMapping("/publications/{publicationId}/likes/summary")
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
    public Page<LikeResponse> getPublicationLikes(

            @PathVariable
            UUID publicationId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return likeService.getPublicationLikes(
                publicationId,
                pageable
        );
    }


    /* ---------------------------------------------------------------
     * Comentário
     * --------------------------------------------------------------- */

    @PostMapping("/comments/{commentId}/likes")
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
    public ResponseEntity<Void> unlikeComment(

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


        return ResponseEntity
                .noContent()
                .build();
    }


    @GetMapping("/comments/{commentId}/likes/summary")
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
    public Page<LikeResponse> getCommentLikes(

            @PathVariable
            UUID commentId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return likeService.getCommentLikes(
                commentId,
                pageable
        );
    }
}
