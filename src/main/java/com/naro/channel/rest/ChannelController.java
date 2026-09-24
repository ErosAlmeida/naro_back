package com.naro.channel.rest;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.application.dto.UpdateChannelRequest;
import com.naro.channel.application.service.ChannelService;
import com.naro.config.CurrentUser;
import com.naro.user.domain.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.IanaLinkRelations;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "Channels", description = "Canais: a identidade pública que publica, comenta e curte")
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    private final ChannelService channelService;
    private final ChannelModelAssembler channelAssembler;

    public ChannelController(ChannelService channelService,
                             ChannelModelAssembler channelAssembler) {
        this.channelService = channelService;
        this.channelAssembler = channelAssembler;
    }

    /* Antes de /{id}: o Spring casa o caminho literal primeiro. */
    @GetMapping("/me")
    @Operation(summary = "Lista os canais do usuário autenticado")
    public CollectionModel<EntityModel<ChannelResponse>> getMyChannels(@CurrentUser User user) {
        return channelAssembler.toCollectionModel(channelService.getMyChannels(user));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca um canal pelo id")
    public EntityModel<ChannelResponse> getById(@PathVariable UUID id) {
        return channelAssembler.toModel(channelService.getById(id));
    }

    @GetMapping("/slug/{slug}")
    @Operation(summary = "Busca um canal pelo slug")
    public EntityModel<ChannelResponse> getBySlug(@PathVariable String slug) {
        return channelAssembler.toModel(channelService.getBySlug(slug));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Atualiza um canal do usuário autenticado")
    public EntityModel<ChannelResponse> update(@PathVariable UUID id,
                                               @Valid @ModelAttribute UpdateChannelRequest request,
                                               @CurrentUser User user) {
        return channelAssembler.toModel(channelService.update(id, request, user));
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um canal para o usuário autenticado")
    public ResponseEntity<EntityModel<ChannelResponse>> create(

            @Valid
            @ModelAttribute
            CreateChannelRequest request,

            @CurrentUser
            User user

    ) {

        EntityModel<ChannelResponse> channel =
                channelAssembler.toModel(channelService.createChannel(request, user));

        return ResponseEntity
                .created(channel.getRequiredLink(IanaLinkRelations.SELF).toUri())
                .body(channel);
    }
}
