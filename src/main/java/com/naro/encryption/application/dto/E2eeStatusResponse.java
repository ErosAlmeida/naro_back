package com.naro.encryption.application.dto;

/**
 * Feature flags vistas pelo cliente. Com required=true o frontend
 * só libera o chat privado com criptografia ponta a ponta.
 */
public record E2eeStatusResponse(

        boolean available,

        boolean required

) {
}
