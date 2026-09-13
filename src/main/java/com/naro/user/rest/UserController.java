package com.naro.user.rest;

import com.naro.config.CurrentUser;
import com.naro.user.application.dto.CurrentUserResponse;
import com.naro.user.domain.entity.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/me")
    public CurrentUserResponse me(@CurrentUser User user) {

        return new CurrentUserResponse(
                user.getKeycloakId(),
                user.getUsername(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName()
        );
    }

    @GetMapping("/me2")
    public String me() {

        return "me";
    }
}