package com.naro.publication.rest;

import com.naro.config.CurrentUser;
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
 * O like é sempre do usuário autenticado: nenhum endpoint recebe userId.
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


    public LikeController(
            LikeService likeService
    ) {
        this.likeService = likeService;
    }


    /* ---------------------------------------------------------------
     * Publicação
     * --------------------------------------------------------------- */

    @PostMapping("/publications/{publicationId}/likes")
    public LikeSummaryResponse likePublication(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User currentUser

    ) {

        return likeService.likePublication(
                publicationId,
                currentUser
        );
    }


    @DeleteMapping("/publications/{publicationId}/likes/me")
    public ResponseEntity<Void> unlikePublication(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User currentUser

    ) {

        likeService.unlikePublication(
                publicationId,
                currentUser
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
            User currentUser

    ) {

        return likeService.getPublicationLikeSummary(
                publicationId,
                currentUser
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

        return likeService.getPublicationLikes(
                publicationId,
                pageRequest(page, size)
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
            User currentUser

    ) {

        return likeService.likeComment(
                commentId,
                currentUser
        );
    }


    @DeleteMapping("/comments/{commentId}/likes/me")
    public ResponseEntity<Void> unlikeComment(

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser

    ) {

        likeService.unlikeComment(
                commentId,
                currentUser
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
            User currentUser

    ) {

        return likeService.getCommentLikeSummary(
                commentId,
                currentUser
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

        return likeService.getCommentLikes(
                commentId,
                pageRequest(page, size)
        );
    }


    private static PageRequest pageRequest(int page, int size) {

        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, 100)
        );
    }
}
