package com.naro.security;

import org.springframework.security.core.AuthenticatedPrincipal;

import java.util.UUID;

/**
 * Identidade de uma conexão WebSocket/STOMP.
 *
 * Criada no CONNECT (via ticket ou JWT) e nunca a partir
 * do payload das mensagens. getName() devolve o keycloakId,
 * que é o nome usado em convertAndSendToUser.
 *
 * Implementa AuthenticatedPrincipal (e não java.security.Principal)
 * para que @AuthenticationPrincipal injete este objeto; um
 * parâmetro do tipo Principal seria resolvido pelo Spring
 * como a Authentication inteira.
 */
public record RealtimePrincipal(

        UUID userId,

        String keycloakId

) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return keycloakId;
    }
}
