package com.naro.chat.config;

import com.naro.security.KeycloakJwtAuthenticationConverter;

import org.springframework.http.HttpHeaders;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;

import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

import org.springframework.security.authentication.BadCredentialsException;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.stereotype.Component;

@Component
public class WebSocketJwtAuthenticationInterceptor
        implements ChannelInterceptor {

    private final JwtDecoder jwtDecoder;

    private final KeycloakJwtAuthenticationConverter
            authenticationConverter;


    public WebSocketJwtAuthenticationInterceptor(
            JwtDecoder jwtDecoder,
            KeycloakJwtAuthenticationConverter authenticationConverter
    ) {
        this.jwtDecoder = jwtDecoder;
        this.authenticationConverter =
                authenticationConverter;
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


        String authorization =
                accessor.getFirstNativeHeader(
                        HttpHeaders.AUTHORIZATION
                );


        if (
                authorization == null
                        || !authorization.startsWith(
                        "Bearer "
                )
        ) {

            throw new BadCredentialsException(
                    "Keycloak Bearer token is required"
            );
        }


        String token =
                authorization.substring(7);


        /*
         * É o mesmo JwtDecoder configurado pelo
         * issuer-uri do Keycloak.
         */
        Jwt jwt =
                jwtDecoder.decode(token);


        JwtAuthenticationToken authentication =
                authenticationConverter.convert(jwt);


        accessor.setUser(authentication);


        return message;
    }
}