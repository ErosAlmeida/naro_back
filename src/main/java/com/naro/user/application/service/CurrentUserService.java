package com.naro.user.application.service;

import com.naro.security.RealtimePrincipal;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("currentUserService")
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User resolve(Jwt jwt) {

        String keycloakId = jwt.getSubject();

        return userRepository
                .findByKeycloakId(keycloakId)
                .orElseGet(() -> createUser(jwt));
    }

    /**
     * Usuário de uma conexão WebSocket já autenticada no CONNECT.
     */
    @Transactional(readOnly = true)
    public User resolveRealtime(RealtimePrincipal principal) {

        return userRepository
                .findById(principal.userId())
                .orElseThrow(() -> new BadCredentialsException(
                        "Authenticated user no longer exists"
                ));
    }

    private User createUser(Jwt jwt) {

        userRepository.provisionIfAbsent(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")
        );

        // Under READ COMMITTED this sees either our insert or the winner of a
        // concurrent insert. The database operation waits for that transaction.
        // Never resolve a username collision as someone else's account.
        return userRepository.findByKeycloakId(jwt.getSubject())
                .orElseThrow(() -> new BadCredentialsException("Authenticated subject could not be provisioned"));
    }
}