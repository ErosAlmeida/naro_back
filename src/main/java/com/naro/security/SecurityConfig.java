package com.naro.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserProvisioningFilter userProvisioningFilter,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        return http

                .csrf(
                        AbstractHttpConfigurer::disable
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        .requestMatchers(
                                "/api/public/**"
                        )
                        .permitAll()

                        /*
                         * O handshake WebSocket precisa ser permitido.
                         *
                         * A autenticação real ocorre depois,
                         * no frame STOMP CONNECT.
                         */
                        .requestMatchers(
                                "/ws",
                                "/ws/**"
                        )
                        .permitAll()

                        .anyRequest()
                        .authenticated()
                )

                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )

                .addFilterAfter(
                        userProvisioningFilter,
                        BearerTokenAuthenticationFilter.class
                )

                .build();
    }
}