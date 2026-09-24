package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.channel.application.service.ChannelService;
import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.service.PublicationService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.hateoas.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Publications", description = "Publicações com imagem, assinadas por um canal")
@RestController
@RequestMapping("/api/v1/publications")
public class PublicationController {

    private final PublicationService publicationService;
    private final ChannelService channelService;
    private final PublicationModelAssembler publicationAssembler;

    public PublicationController(
            PublicationService publicationService,
            ChannelService channelService,
            PublicationModelAssembler publicationAssembler
    ) {
        this.publicationService = publicationService;
        this.channelService = channelService;
        this.publicationAssembler = publicationAssembler;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Publica uma imagem com texto em nome do canal selecionado")
    public ResponseEntity<EntityModel<PublicationResponse>> create(

            @Valid
            @ModelAttribute
            CreatePublicationRequest request,

            @CurrentUser
            User user,

            @RequestHeader("X-Channel-Id")
            UUID channelId

    ) {

        EntityModel<PublicationResponse> publication =
                publicationAssembler.toModel(
                        publicationService.create(
                                request,
                                channelService.getOwnedChannel(channelId, user)
                        )
                );

        return ResponseEntity
                .created(publication.getRequiredLink(IanaLinkRelations.SELF).toUri())
                .body(publication);
    }


    @GetMapping("/{publicationId}")
    @Operation(summary = "Busca uma publicação")
    public EntityModel<PublicationResponse> getById(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User user,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId

    ) {

        return publicationAssembler.toModel(
                publicationService.getById(
                        publicationId,
                        channelId == null ? null : channelService.getOwnedChannel(channelId, user)
                )
        );
    }


    @GetMapping
    @Operation(summary = "Lista publicações, mais recentes primeiro; authorId filtra por canal")
    public PagedModel<EntityModel<PublicationResponse>> getPublications(

            @RequestParam(
                    required = false
            )
            UUID authorId,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size,

            @CurrentUser
            User user,

            @RequestHeader(value = "X-Channel-Id", required = false)
            UUID channelId,

            PagedResourcesAssembler<PublicationResponse> pagedAssembler

    ) {

        int safePage =
                Math.max(
                        page,
                        0
                );


        int safeSize =
                Math.clamp(
                        size,
                        1,
                        100
                );


        return pagedAssembler.toModel(
                publicationService.getPublications(
                        authorId,
                        channelId == null ? null : channelService.getOwnedChannel(channelId, user),
                        PageRequest.of(
                                safePage,
                                safeSize
                        )
                ),
                publicationAssembler
        );
    }
}
