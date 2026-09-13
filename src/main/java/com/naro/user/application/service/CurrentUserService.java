package com.naro.user.application.service;

import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
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

    private User createUser(Jwt jwt) {

        User user = new User(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")
        );

        return userRepository.save(user);
    }
}