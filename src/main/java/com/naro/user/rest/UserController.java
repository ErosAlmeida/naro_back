package com.naro.user.rest;

import com.naro.config.CurrentUser;
import com.naro.user.application.dto.CurrentUserResponse;
import com.naro.user.application.dto.UserProfileResponse;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.application.mapper.UserMapper;
import com.naro.user.application.service.UserService;
import com.naro.user.domain.entity.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Users", description = "Usuário autenticado, perfis públicos e busca")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    private final UserMapper userMapper;

    private final CurrentUserModelAssembler currentUserAssembler;

    private final UserProfileModelAssembler profileAssembler;

    private final UserSummaryModelAssembler summaryAssembler;


    public UserController(
            UserService userService,
            UserMapper userMapper,
            CurrentUserModelAssembler currentUserAssembler,
            UserProfileModelAssembler profileAssembler,
            UserSummaryModelAssembler summaryAssembler
    ) {
        this.userService = userService;
        this.userMapper = userMapper;
        this.currentUserAssembler = currentUserAssembler;
        this.profileAssembler = profileAssembler;
        this.summaryAssembler = summaryAssembler;
    }


    @GetMapping("/me")
    @Operation(summary = "Usuário autenticado, com links para os canais e as conversas dele")
    public EntityModel<CurrentUserResponse> me(@CurrentUser User user) {

        return currentUserAssembler.toModel(userMapper.toCurrentUserResponse(user));
    }

    @GetMapping("/me2")
    public String me() {

        return "me";
    }


    @GetMapping("/search")
    @Operation(summary = "Busca usuários por username ou nome, em ordem alfabética")
    public PagedModel<EntityModel<UserSummaryResponse>> search(

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
            User currentUser,

            PagedResourcesAssembler<UserSummaryResponse> pagedAssembler

    ) {

        return pagedAssembler.toModel(
                userService.search(
                        query,
                        currentUser,
                        PageRequest.of(
                                Math.max(page, 0),
                                Math.clamp(size, 1, 50)
                        )
                ),
                summaryAssembler
        );
    }


    @GetMapping("/{userId}")
    @Operation(summary = "Perfil público de um usuário")
    public EntityModel<UserProfileResponse> getProfile(

            @PathVariable
            UUID userId

    ) {

        return profileAssembler.toModel(userService.getProfile(userId));
    }
}
