package com.naro.realtime.application.service;

import com.naro.realtime.application.dto.WebSocketTicketResponse;
import com.naro.security.RealtimePrincipal;
import com.naro.user.domain.entity.User;

import java.util.Optional;

/**
 * Credencial efêmera para o CONNECT do WebSocket.
 *
 * O browser nunca recebe o access token: o BFF (Next.js)
 * pede um ticket com o token no servidor e repassa apenas
 * o ticket, que vale para uma única conexão.
 */
public interface WebSocketTicketService {

    WebSocketTicketResponse issue(
            User user
    );


    /**
     * Consome o ticket (uso único).
     * Vazio quando inexistente, já usado ou expirado.
     */
    Optional<RealtimePrincipal> consume(
            String ticket
    );
}
