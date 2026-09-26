package com.naro.security;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Channel-Id"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        // /ws já valida origins no WebSocketConfig e preserva sua configuração.
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            UserProvisioningFilter userProvisioningFilter,
            KeycloakJwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        return http

                .cors(withDefaults())

                .csrf(
                        AbstractHttpConfigurer::disable
                )

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        /*
                         * O container reenvia os erros para /error.
                         * Sem isso, um 400/404 numa rota pública
                         * (como o Swagger) chegaria ao cliente como 401.
                         */
                        .dispatcherTypeMatchers(
                                DispatcherType.ERROR
                        )
                        .permitAll()

                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()

                        .requestMatchers("/actuator", "/actuator/**")
                        .denyAll()

                        .requestMatchers(
                                "/api/public/**"
                        )
                        .permitAll()

                        /*
                         * Swagger UI e contrato OpenAPI.
                         *
                         * Só a documentação é pública: o "Try it out"
                         * continua exigindo o token do Keycloak.
                         */
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
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
