package com.naro.encryption.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flags da criptografia ponta a ponta do chat privado.
 *
 *   available  endpoints de chaves ligados; o servidor aceita mensagens E2EE
 *   required   chat privado somente E2EE: toda mensagem nova sem
 *              criptografia é recusada com E2EE_REQUIRED
 *
 * Fase 1: available=true, required=false (infraestrutura no ar, chat inalterado).
 * Fase 2: required=true. Veja docs/e2ee.md.
 */
@ConfigurationProperties(prefix = "naro.security.e2ee")
public record E2eeProperties(

        boolean available,

        boolean required

) {
    public E2eeProperties {
        if (required && !available) {
            // Exigir E2EE sem os endpoints de chaves bloquearia todo o chat.
            throw new IllegalStateException(
                    "naro.security.e2ee.required=true requires naro.security.e2ee.available=true");
        }
    }
}
