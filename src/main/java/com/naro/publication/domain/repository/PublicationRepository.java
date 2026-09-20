package com.naro.publication.domain.repository;

import com.naro.publication.domain.entity.Publication;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PublicationRepository
        extends JpaRepository<Publication, UUID> {


    @EntityGraph(attributePaths = "author")
    Optional<Publication> findWithAuthorById(
            UUID id
    );


    @EntityGraph(attributePaths = "author")
    Page<Publication> findAllByOrderByCreatedAtDescIdDesc(
            Pageable pageable
    );


    @EntityGraph(attributePaths = "author")
    Page<Publication> findByAuthor_IdOrderByCreatedAtDescIdDesc(
            UUID authorId,
            Pageable pageable
    );
}
