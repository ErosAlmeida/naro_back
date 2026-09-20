package com.naro.user.rest;

import com.naro.config.CurrentUser;
import com.naro.user.application.dto.CurrentUserResponse;
import com.naro.user.application.dto.UserProfileResponse;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.application.mapper.UserMapper;
import com.naro.user.application.service.UserService;
import com.naro.user.domain.entity.User;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    private final UserMapper userMapper;


    public UserController(
            UserService userService,
            UserMapper userMapper
    ) {
        this.userService = userService;
        this.userMapper = userMapper;
    }


    @GetMapping("/me")
    public CurrentUserResponse me(@CurrentUser User user) {

        return userMapper.toCurrentUserResponse(user);
    }

    @GetMapping("/me2")
    public String me() {

        return "me";
    }


    @GetMapping("/search")
    public Page<UserSummaryResponse> search(

            @RequestParam("q")
            String query,

            @RequestParam(
                    defaultValue = "0"
            )
            int page,

            @RequestParam(
                    defaultValue = "20"
            )
            int size,

            @CurrentUser
            User currentUser

    ) {

        return userService.search(
                query,
                currentUser,
                PageRequest.of(
                        Math.max(page, 0),
                        Math.clamp(size, 1, 50)
                )
        );
    }


    @GetMapping("/{userId}")
    public UserProfileResponse getProfile(

            @PathVariable
            UUID userId

    ) {

        return userService.getProfile(userId);
    }
}
