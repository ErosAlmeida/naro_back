package com.naro.user.application.dto;

import org.springframework.hateoas.server.core.Relation;

import java.util.UUID;

@Relation(collectionRelation = "users")
public record UserSummaryResponse(

        UUID id,

        String username,

        String firstName,

        String lastName,

        String avatarUrl

) {
}
