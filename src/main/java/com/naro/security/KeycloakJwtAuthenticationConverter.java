package com.naro.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, JwtAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter
            scopeAuthoritiesConverter =
            new JwtGrantedAuthoritiesConverter();


    @Override
    public JwtAuthenticationToken convert(
            @NonNull Jwt jwt
    ) {


        Collection<GrantedAuthority> scopeAuthorities =
                scopeAuthoritiesConverter.convert(jwt);


        Set<GrantedAuthority> authorities =
                new LinkedHashSet<>(scopeAuthorities);


        Object realmAccessClaim =
                jwt.getClaim("realm_access");


        if (realmAccessClaim instanceof Map<?, ?> realmAccess) {

            Object rolesClaim =
                    realmAccess.get("roles");


            if (rolesClaim
                    instanceof Collection<?> roles) {

                roles
                        .stream()
                        .map(String::valueOf)
                        .map(
                                role ->
                                        new SimpleGrantedAuthority(
                                                "ROLE_" + role
                                        )
                        )
                        .forEach(authorities::add);
            }
        }


        /*
         * Usamos o subject do Keycloak como nome do Principal.
         *
         * Isso é importante porque:
         *
         * Authentication#getName()
         * =
         * jwt.getSubject()
         * =
         * User.keycloakId
         *
         * Então convertAndSendToUser(keycloakId, ...)
         * consegue localizar exatamente o usuário conectado.
         */
        return new JwtAuthenticationToken(
                jwt,
                authorities,
                Objects.requireNonNull(jwt.getSubject())
        );
    }
}