package com.naro.encryption.rest;

import com.naro.encryption.application.dto.EncryptionIdentityResponse;

import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Links da identidade do usuário autenticado:
 *
 *   self   GET /api/v1/encryption/identity
 *   keys   GET /api/v1/encryption/keys
 *
 * methodOn só lê o que vai na URL: o usuário vai como null.
 */
@Component
public class EncryptionIdentityModelAssembler
        implements RepresentationModelAssembler<EncryptionIdentityResponse, EntityModel<EncryptionIdentityResponse>> {

    @Override
    public EntityModel<EncryptionIdentityResponse> toModel(EncryptionIdentityResponse identity) {
        return EntityModel.of(
                identity,
                self(),
                keys()
        );
    }

    private Link self() {
        return linkTo(methodOn(EncryptionController.class)
                .identity(null))
                .withSelfRel();
    }

    private Link keys() {
        return linkTo(methodOn(EncryptionController.class)
                .keys(null))
                .withRel("keys");
    }
}
