package com.naro.user.rest;

import com.naro.exception.BadRequestException;
import com.naro.exception.NotFoundException;
import com.naro.security.SecurityConfig;
import com.naro.user.application.mapper.UserMapperImpl;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.application.service.UserService;
import com.naro.user.domain.entity.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({
        SecurityConfig.class,
        UserMapperImpl.class,
        CurrentUserModelAssembler.class,
        UserProfileModelAssembler.class,
        UserSummaryModelAssembler.class
})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user();


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }


    /*
     * O id exposto é o UUID interno (o mesmo de authorId/senderId),
     * não o subject do Keycloak.
     */
    @Test
    void meReturnsInternalUuidAndNotKeycloakSubject() throws Exception {

        mockMvc.perform(get("/api/users/me").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(currentUser.getId().toString()))
                .andExpect(jsonPath("$.username").value("leonardo"))
                .andExpect(jsonPath("$.keycloakId").doesNotExist());
    }


    /*
     * /me é a porta de entrada: dele se chega aos canais
     * e às conversas do usuário.
     */
    @Test
    void meLinksToTheUsersChannelsAndConversations() throws Exception {

        mockMvc.perform(get("/api/users/me").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.self.href").value("http://localhost/api/users/me"))
                .andExpect(jsonPath("$._links.channels.href").value("http://localhost/api/v1/channels/me"))
                .andExpect(jsonPath("$._links.conversations.href").value("http://localhost/api/v1/chats"));
    }


    @Test
    void meWithoutTokenReturns401() throws Exception {

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    void searchWithInvalidQueryReturns400() throws Exception {

        when(userService.search(eq("a"), eq(currentUser), any()))
                .thenThrow(new BadRequestException("Search query must have between 2 and 50 characters"));

        mockMvc.perform(get("/api/users/search").param("q", "a").with(jwt()))
                .andExpect(status().isBadRequest());
    }


    @Test
    void profileOfUnknownUserReturns404() throws Exception {

        UUID unknown = UUID.randomUUID();

        when(userService.getProfile(unknown)).thenThrow(new NotFoundException("User not found"));

        mockMvc.perform(get("/api/users/{userId}", unknown).with(jwt()))
                .andExpect(status().isNotFound());
    }


    private static User user() {

        User user = new User("kc-" + UUID.randomUUID(), "leonardo", "leo@naro.dev", "Leonardo", "Vilela");

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
