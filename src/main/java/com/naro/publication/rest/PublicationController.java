package com.naro.publication.rest;

import com.naro.config.CurrentUser;
import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.service.PublicationService;
import com.naro.user.domain.entity.User;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

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


    @GetMapping("/{publicationId}")
    public PublicationResponse getById(

            @PathVariable
            UUID publicationId,

            @CurrentUser
            User user

    ) {

        return publicationService.getById(
                publicationId,
                user
        );
    }


    @GetMapping
    public Page<PublicationResponse> getPublications(

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
            User user

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


        return publicationService.getPublications(
                authorId,
                user,
                PageRequest.of(
                        safePage,
                        safeSize
                )
        );
    }
}
