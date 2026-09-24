package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.channel.application.service.ChannelService;
import com.naro.publication.application.dto.CommentResponse;
import com.naro.publication.application.dto.CreateCommentRequest;
import com.naro.publication.application.service.CommentService;
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
@Tag(name = "Comments", description = "Comentários e respostas, em qualquer nível")
@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService commentService;
    private final ChannelService channelService;
    private final CommentModelAssembler commentAssembler;


    public CommentController(
            CommentService commentService,
            ChannelService channelService,
            CommentModelAssembler commentAssembler
    ) {
        this.commentService = commentService;
        this.channelService = channelService;
        this.commentAssembler = commentAssembler;
    }


    @PostMapping("/publications/{publicationId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comenta uma publicação")
    public ResponseEntity<EntityModel<CommentResponse>> commentOnPublication(

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

        return ResponseEntity.created(location).body(commentAssembler.toModel(response));
    }


    @PostMapping("/comments/{commentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Responde um comentário")
    public ResponseEntity<EntityModel<CommentResponse>> reply(

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

        return ResponseEntity.created(location).body(commentAssembler.toModel(response));
    }


    @GetMapping("/publications/{publicationId}/comments")
    @Operation(summary = "Lista os comentários diretos de uma publicação, mais recentes primeiro")
    public PagedModel<EntityModel<CommentResponse>> getPublicationComments(

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
            UUID channelId,

            PagedResourcesAssembler<CommentResponse> pagedAssembler

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return pagedAssembler.toModel(
                commentService.getPublicationComments(
                        publicationId,
                        channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser),
                        pageable
                ),
                commentAssembler
        );
    }


    @GetMapping("/comments/{commentId}/comments")
    @Operation(summary = "Lista as respostas diretas de um comentário, mais antigas primeiro")
    public PagedModel<EntityModel<CommentResponse>> getReplies(

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
            UUID channelId,

            PagedResourcesAssembler<CommentResponse> pagedAssembler

    ) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);
        PageRequest pageable = PageRequest.of(safePage, safeSize);

        return pagedAssembler.toModel(
                commentService.getReplies(
                        commentId,
                        channelId == null ? null : channelService.getOwnedChannel(channelId, currentUser),
                        pageable
                ),
                commentAssembler
        );
    }


    @DeleteMapping("/publications/{publicationId}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Apaga um comentário do canal selecionado, em qualquer nível")
    public void delete(

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

    }
}
