package com.naro.publication.application.mapper;

import com.naro.publication.application.dto.PublicationResponse;
import com.naro.publication.domain.entity.Publication;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface PublicationMapper {

    @Mapping(
            target = "authorId",
            source = "publication.author.id"
    )
    @Mapping(
            target = "authorUsername",
            source = "publication.author.username"
    )
    @Mapping(
            target = "imageUrl",
            source = "imageUrl"
    )
    PublicationResponse toResponse(
            Publication publication,
            String imageUrl
    );
}