package com.naro.user.rest;

import com.naro.user.application.dto.UserSummaryResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links de um usuário na busca. O resumo aponta para o perfil completo:
 *
 *   self   GET /api/users/{id}
 */
@Component
public class UserSummaryModelAssembler
        implements RepresentationModelAssembler<UserSummaryResponse, EntityModel<UserSummaryResponse>> {

    @Override
    public EntityModel<UserSummaryResponse> toModel(UserSummaryResponse user) {
        return EntityModel.of(
                user,
                linkTo(methodOn(UserController.class).getProfile(user.id())).withSelfRel()
        );
    }
}
