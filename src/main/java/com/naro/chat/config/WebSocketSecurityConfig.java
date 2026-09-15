package com.naro.chat.config;

import org.springframework.context.annotation.Configuration;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import org.springframework.messaging.Message;

import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;

import org.springframework.messaging.simp.SimpMessageType;

import org.springframework.messaging.simp.config.ChannelRegistration;

import org.springframework.security.authorization.AuthorizationManager;

import org.springframework.security.messaging.access.intercept.AuthorizationChannelInterceptor;

import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import org.springframework.security.messaging.context.AuthenticationPrincipalArgumentResolver;

import org.springframework.security.messaging.context.SecurityContextChannelInterceptor;

import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketSecurityConfig
        implements WebSocketMessageBrokerConfigurer {

    private final WebSocketJwtAuthenticationInterceptor
            jwtAuthenticationInterceptor;


    public WebSocketSecurityConfig(
            WebSocketJwtAuthenticationInterceptor
                    jwtAuthenticationInterceptor
    ) {

        this.jwtAuthenticationInterceptor =
                jwtAuthenticationInterceptor;
    }


    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {

        AuthorizationChannelInterceptor
                authorizationInterceptor =
                new AuthorizationChannelInterceptor(
                        authorizationManager()
                );


        registration.interceptors(

                /*
                 * 1 - autentica Keycloak JWT
                 */
                jwtAuthenticationInterceptor,

                /*
                 * 2 - coloca Authentication
                 * no SecurityContext
                 */
                new SecurityContextChannelInterceptor(),

                /*
                 * 3 - verifica autorização
                 */
                authorizationInterceptor
        );
    }


    @Override
    public void addArgumentResolvers(
            List<HandlerMethodArgumentResolver>
                    argumentResolvers
    ) {

        /*
         * Permite:
         *
         * @AuthenticationPrincipal Jwt jwt
         *
         * em @MessageMapping.
         */
        argumentResolvers.add(
                new AuthenticationPrincipalArgumentResolver()
        );
    }


    private AuthorizationManager<Message<?>>
    authorizationManager() {

        var messages =
                MessageMatcherDelegatingAuthorizationManager
                        .builder();


        messages

                /*
                 * CONNECT / DISCONNECT etc.
                 *
                 * CONNECT já precisa estar autenticado
                 * pelo interceptor anterior.
                 */
                .nullDestMatcher()
                .authenticated()


                /*
                 * Cliente pode enviar somente
                 * para nossos handlers.
                 */
                .simpDestMatchers(
                        "/app/chat/**"
                )
                .authenticated()


                /*
                 * Cliente pode ouvir apenas
                 * filas privadas dele.
                 */
                .simpSubscribeDestMatchers(
                        "/user/queue/chat",
                        "/user/queue/errors"
                )
                .authenticated()


                /*
                 * Qualquer outro SEND ou SUBSCRIBE
                 * é bloqueado.
                 */
                .simpTypeMatchers(
                        SimpMessageType.MESSAGE,
                        SimpMessageType.SUBSCRIBE
                )
                .denyAll()


                .anyMessage()
                .denyAll();


        return messages.build();
    }
}