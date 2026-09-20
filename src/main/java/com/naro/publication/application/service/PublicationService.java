package com.naro.publication.application.service;

import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PublicationService {

    PublicationResponse create(
            CreatePublicationRequest request,
            User author
    );


    PublicationResponse getById(
            UUID publicationId,
            User currentUser
    );


    /*
     * authorId opcional: sem ele, retorna as publicações
     * mais recentes de todos os autores.
     */
    Page<PublicationResponse> getPublications(
            UUID authorId,
            User currentUser,
            Pageable pageable
    );
}
