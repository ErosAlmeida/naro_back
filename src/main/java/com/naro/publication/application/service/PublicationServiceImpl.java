package com.naro.publication.application.service;

import com.naro.publication.application.dto.CreatePublicationRequest;
import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.application.mapper.PublicationMapper;
import com.naro.publication.domain.entity.Publication;
import com.naro.publication.domain.repository.PublicationRepository;
import com.naro.storage.service.ObjectStorageService;
import com.naro.user.domain.entity.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationServiceImpl implements PublicationService {

    private final PublicationRepository publicationRepository;
    private final PublicationMapper publicationMapper;
    private final ObjectStorageService storageService;

    public PublicationServiceImpl(
            PublicationRepository publicationRepository,
            PublicationMapper publicationMapper,
            ObjectStorageService storageService
    ) {
        this.publicationRepository = publicationRepository;
        this.publicationMapper = publicationMapper;
        this.storageService = storageService;
    }

    @Override
    @Transactional
    public PublicationResponse create(
            CreatePublicationRequest request,
            User author
    ) {

        String imageKey = storageService.upload(
                "publications",
                request.getImage()
        );

        try {

            Publication publication = new Publication(
                    author,
                    request.getContent(),
                    imageKey
            );

            Publication saved =
                    publicationRepository.saveAndFlush(publication);

            String imageUrl =
                    storageService.createReadUrl(
                            saved.getImageKey()
                    );

            return publicationMapper.toResponse(
                    saved,
                    imageUrl
            );

        } catch (RuntimeException exception) {

            deleteImageSafely(imageKey);

            throw exception;
        }
    }

    private void deleteImageSafely(String imageKey) {

        try {
            storageService.delete(imageKey);
        } catch (RuntimeException ignored) {
            // adicionar log depois
        }
    }
}