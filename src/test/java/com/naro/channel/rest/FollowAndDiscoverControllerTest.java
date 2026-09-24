package com.naro.channel.rest;

import com.naro.channel.application.dto.DiscoverChannelResponse;
import com.naro.channel.application.dto.FollowResponse;
import com.naro.channel.application.dto.FollowingStatusResponse;
import com.naro.channel.application.service.DiscoverService;
import com.naro.channel.application.service.FollowService;
import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.security.SecurityConfig;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.naro.support.Fixtures.user;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({FollowController.class, DiscoverController.class})
@Import({SecurityConfig.class, FollowChannelModelAssembler.class, DiscoverChannelModelAssembler.class})
class FollowAndDiscoverControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FollowService followService;
    @MockitoBean private DiscoverService discoverService;
    @MockitoBean(name = "currentUserService") private CurrentUserService currentUserService;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final User currentUser = user("owner");
    private final UUID activeChannelId = UUID.randomUUID();
    private final UUID targetChannelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }

    @Test
    void followAndUnfollowUseTheAuthenticatedUserAndExpectedHttpStatuses() throws Exception {
        var follow = new FollowResponse(UUID.randomUUID(), activeChannelId, targetChannelId,
                false, 0, null, null, Instant.now());
        when(followService.follow(activeChannelId, targetChannelId, currentUser)).thenReturn(follow);

        mockMvc.perform(post("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.followerChannelId").value(activeChannelId.toString()))
                .andExpect(jsonPath("$.followingChannelId").value(targetChannelId.toString()))
                .andExpect(jsonPath("$.favorite").value(false));
        mockMvc.perform(delete("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId).with(jwt()))
                .andExpect(status().isNoContent());

        verify(followService).follow(activeChannelId, targetChannelId, currentUser);
        verify(followService).unfollow(activeChannelId, targetChannelId, currentUser);
    }

    @Test
    void followFailuresMapToBadRequestConflictAndNotFound() throws Exception {
        when(followService.follow(activeChannelId, targetChannelId, currentUser))
                .thenThrow(new BadRequestException("Self follow"))
                .thenThrow(new ConflictException("Duplicate follow"))
                .thenThrow(new NotFoundException("Channel not found"));

        for (int expectedStatus : new int[]{400, 409, 404}) {
            mockMvc.perform(post("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId).with(jwt()))
                    .andExpect(status().is(expectedStatus));
        }
    }

    @Test
    void foreignActiveChannelReturnsForbiddenForEveryProtectedAction() throws Exception {
        when(followService.follow(activeChannelId, targetChannelId, currentUser))
                .thenThrow(new ForbiddenException("Not your channel"));
        org.mockito.Mockito.doThrow(new ForbiddenException("Not your channel"))
                .when(followService).unfollow(activeChannelId, targetChannelId, currentUser);
        when(followService.setFavorite(activeChannelId, targetChannelId, true, currentUser))
                .thenThrow(new ForbiddenException("Not your channel"));
        when(discoverService.discover(activeChannelId, null, PageRequest.of(0, 20), currentUser))
                .thenThrow(new ForbiddenException("Not your channel"));

        mockMvc.perform(post("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId).with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId).with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/channels/{channelId}/following/{targetChannelId}/favorite", activeChannelId, targetChannelId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}").with(jwt()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/channels/discover").header("X-Channel-Id", activeChannelId).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusAndCountsAreReturnedAsSmallObjects() throws Exception {
        when(followService.isFollowing(activeChannelId, targetChannelId, currentUser))
                .thenReturn(new FollowingStatusResponse(true, true));
        when(followService.countFollowing(activeChannelId)).thenReturn(3L);
        when(followService.countFollowers(activeChannelId)).thenReturn(5L);

        mockMvc.perform(get("/api/v1/channels/{channelId}/following/{targetChannelId}/status", activeChannelId, targetChannelId).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.following").value(true))
                .andExpect(jsonPath("$.favorite").value(true));
        mockMvc.perform(get("/api/v1/channels/{channelId}/following/count", activeChannelId).with(jwt()))
                .andExpect(jsonPath("$.count").value(3));
        mockMvc.perform(get("/api/v1/channels/{channelId}/followers/count", activeChannelId).with(jwt()))
                .andExpect(jsonPath("$.count").value(5));
    }

    @Test
    void discoverReturnsPagedHalCardsAndPassesTheActiveChannel() throws Exception {
        var page = PageRequest.of(1, 100);
        var channel = new DiscoverChannelResponse(targetChannelId, "Spring Brasil", "spring-brasil",
                "Java e Spring", null, 42, true);
        when(discoverService.discover(activeChannelId, "spring", page, currentUser))
                .thenReturn(new PageImpl<>(List.of(channel), page, 101));

        mockMvc.perform(get("/api/v1/channels/discover")
                        .header("X-Channel-Id", activeChannelId)
                        .param("search", "spring")
                        .param("page", "1")
                        .param("size", "500")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.discoverChannels[0].id").value(targetChannelId.toString()))
                .andExpect(jsonPath("$._embedded.discoverChannels[0].followersCount").value(42))
                .andExpect(jsonPath("$._embedded.discoverChannels[0].following").value(true))
                .andExpect(jsonPath("$.page.totalElements").value(101));

        verify(discoverService).discover(activeChannelId, "spring", page, currentUser);
    }

    @Test
    void requestsWithoutJwtAreUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/channels/{channelId}/following/{targetChannelId}", activeChannelId, targetChannelId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/channels/discover").header("X-Channel-Id", activeChannelId))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(followService, discoverService);
    }
}
