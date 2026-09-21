package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.channel.application.service.ChannelService;
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
    private final ChannelService channelService;


    public CommentController(
            CommentService commentService,
            ChannelService channelService
    ) {
        this.commentService = commentService;
        this.channelService = channelService;
    }


    @PostMapping("/publications/{publicationId}/comments")
    public ResponseEntity<CommentResponse> commentOnPublication(

            @PathVariable
            UUID publicationId,

            @Valid
            @RequestBody
            CreateCommentRequest request,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        CommentResponse response = commentService.commentOnPublication(
                publicationId,
                request,
                channelService.getOwnedChannel(channelId, currentUser)
        );

        URI location = URI.create(
                "/api/v1/publications/" + response.publicationId() + "/comments/" + response.id()
        );

        return ResponseEntity.created(location).body(response);
    }


    @PostMapping("/comments/{commentId}/comments")
    public ResponseEntity<CommentResponse> reply(

            @PathVariable
            UUID commentId,

            @Valid
            @RequestBody
            CreateCommentRequest request,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        CommentResponse response = commentService.reply(
                commentId,
                request,
                channelService.getOwnedChannel(channelId, currentUser)
        );

        URI location = URI.create(
                "/api/v1/publications/" + response.publicationId() + "/comments/" + response.id()
        );

        return ResponseEntity.created(location).body(response);
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
            User currentUser,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return commentService.getPublicationComments(
                publicationId,
                channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser),
                pageable
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
            User currentUser,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return commentService.getReplies(
                commentId,
                channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser),
                pageable
        );
    }


    @DeleteMapping("/publications/{publicationId}/comments/{commentId}")
    public ResponseEntity<Void> delete(

            @PathVariable
            UUID publicationId,

            @PathVariable
            UUID commentId,

            @CurrentUser
            User currentUser,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        commentService.delete(
                publicationId,
                commentId,
                channelService.getOwnedChannel(channelId, currentUser)
        );


        return ResponseEntity
                .noContent()
                .build();
    }
}
