package com.naro.publication.application.service;

import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.user.domain.entity.User;

public interface PublicationService {

    PublicationResponse create(
            CreatePublicationRequest request,
            User author
    );
}