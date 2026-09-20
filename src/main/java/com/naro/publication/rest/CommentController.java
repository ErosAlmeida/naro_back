package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.publication.application.service.CommentService;
import com.naro.user.domain.entity.User;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * Comentários em qualquer nível:
 *
 *   POST   /publications/{publicationId}/comments           comentar a publicação
 *   GET    /publications/{publicationId}/comments           comentários diretos (paginado)
 *   DELETE /publications/{publicationId}/comments/{id}      apagar (qualquer nível)
 *
 *   POST   /comments/{commentId}/comments                   responder um comentário
 *   GET    /comments/{commentId}/comments                   respostas diretas (paginado)
 */
@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService commentService;


    public CommentController(
            CommentService commentService
    ) {
        this.commentService = commentService;
    }


    @PostMapping("/publications/{publicationId}/comments")
    public ResponseEntity<CommentResponse> commentOnPublication(

            @PathVariable
            UUID publicationId,

            @Valid
            @RequestBody
            CreateCommentRequest request,

            @CurrentUser
            User currentUser

    ) {

        return created(
                commentService.commentOnPublication(
                        publicationId,
                        request,
                        currentUser
                )
        );
    }


    @PostMapping("/comments/{commentId}/comments")
    public ResponseEntity<CommentResponse> reply(

            @PathVariable
            UUID commentId,

            @Valid
            @RequestBody
            CreateCommentRequest request,

            @CurrentUser
            User currentUser

    ) {

        return created(
                commentService.reply(
                        commentId,
                        request,
                        currentUser
                )
        );
    }


    @GetMapping("/publications/{publicationId}/comments")
    public Page<CommentResponse> getPublicationComments(

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

            @CurrentUser
            User currentUser

    ) {

        return commentService.getPublicationComments(
                publicationId,
                currentUser,
                pageRequest(page, size)
        );
    }


    @GetMapping("/comments/{commentId}/comments")
    public Page<CommentResponse> getReplies(

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

            @CurrentUser
            User currentUser

    ) {

        return commentService.getReplies(
                commentId,
                currentUser,
                pageRequest(page, size)
        );
    }


    @DeleteMapping("/publications/{publicationId}/comments/{commentId}")
    public ResponseEntity<Void> delete(

            @PathVariable
            UUID publicationId,

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser

    ) {

        commentService.delete(
                publicationId,
                commentId,
                currentUser
        );


        return ResponseEntity
                .noContent()
                .build();
    }


    private static ResponseEntity<CommentResponse> created(CommentResponse response) {

        URI location = URI.create(
                "/api/v1/publications/"
                        + response.publicationId()
                        + "/comments/"
                        + response.id()
        );

        return ResponseEntity
                .created(location)
                .body(response);
    }


    private static PageRequest pageRequest(int page, int size) {

        return PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, 100)
        );
    }
}
