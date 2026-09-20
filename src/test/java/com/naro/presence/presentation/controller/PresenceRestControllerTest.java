package com.naro.presence.presentation.controller;

import com.naro.presence.application.dto.PresenceResponse;
import com.naro.presence.application.service.PresenceService;
import com.naro.presence.domain.enums.PresenceStatus;
import com.naro.security.SecurityConfig;
import com.naro.user.application.service.CurrentUserService;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PresenceRestController.class)
@Import(SecurityConfig.class)
class PresenceRestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PresenceService presenceService;

    @MockitoBean(name = "currentUserService")
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final User currentUser = user();


    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }


    @Test
    void returnsPresenceOfRequestedUsers() throws Exception {

        UUID maria = UUID.randomUUID();
        Instant lastSeen = Instant.parse("2026-09-18T22:14:00Z");

        when(presenceService.getPresence(Set.of(maria), currentUser))
                .thenReturn(List.of(new PresenceResponse(maria, PresenceStatus.OFFLINE, lastSeen)));

        mockMvc.perform(get("/api/v1/presence").param("userIds", maria.toString()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(maria.toString()))
                .andExpect(jsonPath("$[0].status").value("OFFLINE"))
                .andExpect(jsonPath("$[0].lastSeenAt").value("2026-09-18T22:14:00Z"));
    }


    @Test
    void tooManyUsersReturns400() throws Exception {

        String ids = IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/presence").param("userIds", ids).with(jwt()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(presenceService);
    }


    @Test
    void withoutTokenReturns401() throws Exception {

        mockMvc.perform(get("/api/v1/presence"))
                .andExpect(status().isUnauthorized());
    }


    private static User user() {

        User user = new User(UUID.randomUUID().toString(), "leonardo", null, null, null);

        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return user;
    }
}
