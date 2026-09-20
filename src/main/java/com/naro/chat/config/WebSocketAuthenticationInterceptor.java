package com.naro.chat.config;

import com.naro.realtime.application.service.WebSocketTicketService;
import com.naro.security.KeycloakJwtAuthenticationConverter;
import com.naro.security.RealtimePrincipal;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;

import org.springframework.http.HttpHeaders;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.security.core.GrantedAuthority;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Autentica o frame STOMP CONNECT.
 *
 * Duas credenciais são aceitas:
 *
 * 1. ticket: <ticket efêmero>
 *    Usado pelo frontend (BFF). O browser nunca vê o JWT.
 *
 * 2. Authorization: Bearer <JWT do Keycloak>
 *    Mantido para clientes que já possuem o token.
 *
 * Em ambos os casos o resultado é um RealtimePrincipal:
 * toda mensagem posterior usa essa identidade, nunca
 * um userId vindo do payload.
 */
@Component
public class WebSocketAuthenticationInterceptor
        implements ChannelInterceptor {

    static final String TICKET_HEADER = "ticket";


    private final WebSocketTicketService ticketService;

    private final JwtDecoder jwtDecoder;

    private final KeycloakJwtAuthenticationConverter
            authenticationConverter;

    private final CurrentUserService currentUserService;


    public WebSocketAuthenticationInterceptor(
            WebSocketTicketService ticketService,
            JwtDecoder jwtDecoder,
            KeycloakJwtAuthenticationConverter authenticationConverter,
            CurrentUserService currentUserService
    ) {
        this.ticketService = ticketService;
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter =
                authenticationConverter;
        this.currentUserService = currentUserService;
    }


    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );


        if (
                accessor == null
                        || accessor.getCommand()
                        != StompCommand.CONNECT
        ) {

            return message;
        }


        String ticket =
                accessor.getFirstNativeHeader(
                        TICKET_HEADER
                );


        UsernamePasswordAuthenticationToken authentication =
                ticket != null
                        ? authenticateWithTicket(ticket)
                        : authenticateWithBearerToken(
                                accessor.getFirstNativeHeader(
                                        HttpHeaders.AUTHORIZATION
                                )
                        );


        accessor.setUser(authentication);


        return message;
    }


    private UsernamePasswordAuthenticationToken authenticateWithTicket(
            String ticket
    ) {

        RealtimePrincipal principal =
                ticketService
                        .consume(ticket)
                        .orElseThrow(
                                () -> new BadCredentialsException(
                                        "Invalid or expired WebSocket ticket"
                                )
                        );


        return authenticated(
                principal,
                List.of()
        );
    }


    private UsernamePasswordAuthenticationToken authenticateWithBearerToken(
            String authorization
    ) {

        if (
                authorization == null
                        || !authorization.startsWith(
                        "Bearer "
                )
        ) {

            throw new BadCredentialsException(
                    "WebSocket ticket or Keycloak Bearer token is required"
            );
        }


        /*
         * É o mesmo JwtDecoder configurado pelo
         * issuer-uri do Keycloak.
         */
        Jwt jwt =
                jwtDecoder.decode(
                        authorization.substring(7)
                );


        User user =
                currentUserService.resolve(jwt);


        return authenticated(
                new RealtimePrincipal(
                        user.getId(),
                        user.getKeycloakId()
                ),
                authenticationConverter
                        .convert(jwt)
                        .getAuthorities()
        );
    }


    private static UsernamePasswordAuthenticationToken authenticated(
            RealtimePrincipal principal,
            Collection<? extends GrantedAuthority> authorities
    ) {

        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                authorities
        );
    }
}
