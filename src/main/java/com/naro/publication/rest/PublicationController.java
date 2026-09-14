package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.service.PublicationService;
import com.naro.user.domain.entity.User;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/publications")
public class PublicationController {

    private final PublicationService publicationService;

    public PublicationController(
            PublicationService publicationService
    ) {
        this.publicationService = publicationService;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<PublicationResponse> create(

            @Valid
            @ModelAttribute
            CreatePublicationRequest request,

            @CurrentUser
            User user

    ) {

        PublicationResponse response =
                publicationService.create(
                        request,
                        user
                );

        URI location = URI.create(
                "/api/v1/publications/"
                        + response.id()
        );

        return ResponseEntity
                .created(location)
                .body(response);
    }
}