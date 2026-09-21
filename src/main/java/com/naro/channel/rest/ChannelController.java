package com.naro.channel.rest;

import com.naro.channel.application.dto.ChannelResponse;
import com.naro.channel.application.dto.CreateChannelRequest;
import com.naro.channel.application.dto.UpdateChannelRequest;
import com.naro.channel.application.service.ChannelService;
import com.naro.config.CurrentUser;
import com.naro.user.domain.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    /* Antes de /{id}: o Spring casa o caminho literal primeiro. */
    @GetMapping("/me")
    public List<ChannelResponse> getMyChannels(@CurrentUser User user) {
        return channelService.getMyChannels(user);
    }

    @GetMapping("/{id}")
    public ChannelResponse getById(@PathVariable UUID id) {
        return channelService.getById(id);
    }

    @GetMapping("/slug/{slug}")
    public ChannelResponse getBySlug(@PathVariable String slug) {
        return channelService.getBySlug(slug);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChannelResponse update(@PathVariable UUID id,
                                  @Valid @ModelAttribute UpdateChannelRequest request,
                                  @CurrentUser User user) {
        return channelService.update(id, request, user);
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<ChannelResponse> create(

            @Valid
            @ModelAttribute
            CreateChannelRequest request,

            @CurrentUser
            User user

    ) {

        ChannelResponse response =
                channelService.createChannel(request, user);

        URI location = URI.create(
                "/api/v1/channels/"
                        + response.id()
        );

        return ResponseEntity
                .created(location)
                .body(response);
    }
}
