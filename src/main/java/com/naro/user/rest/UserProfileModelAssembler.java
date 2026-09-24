package com.naro.user.rest;

import com.naro.user.application.dto.UserProfileResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de um perfil público:
 *
 *   self   GET /api/users/{id}
 */
@Component
public class UserProfileModelAssembler
        implements RepresentationModelAssembler<UserProfileResponse, EntityModel<UserProfileResponse>> {

    @Override
    public EntityModel<UserProfileResponse> toModel(UserProfileResponse user) {
        return EntityModel.of(
                user,
                linkTo(methodOn(UserController.class).getProfile(user.id())).withSelfRel()
        );
    }
}
