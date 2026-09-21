package com.naro.publication.application.service;

import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.channel.domain.entity.Channel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PublicationService {

    PublicationResponse create(
            CreatePublicationRequest request,
            Channel author
    );


    PublicationResponse getById(
            UUID publicationId,
            Channel currentChannel
    );


    /*
     * authorId é o UUID do canal autor. Sem ele, retorna as publicações
     * mais recentes de todos os canais.
     */
    Page<PublicationResponse> getPublications(
            UUID authorId,
            Channel currentChannel,
            Pageable pageable
    );
}
