package com.naro.publication.domain.repository;

import com.naro.publication.domain.entity.Publication;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PublicationRepository
        extends JpaRepository<Publication, UUID> {
}