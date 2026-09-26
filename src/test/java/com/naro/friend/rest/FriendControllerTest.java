package com.naro.friend.rest;

import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.friend.application.dto.FriendDiscoveryResponse;
import com.naro.friend.application.dto.FriendRequestResponse;
import com.naro.friend.application.dto.FriendResponse;
import com.naro.friend.application.dto.FriendRelationshipResponse;
import com.naro.friend.application.service.FriendService;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.friend.domain.enums.RelationshipStatus;
import com.naro.security.SecurityConfig;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.application.service.CurrentUserService;
import com.naro.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FriendController.class)
@Import(SecurityConfig.class)
class FriendControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FriendService friendService;
    @MockitoBean(name = "currentUserService") private CurrentUserService currentUserService;
    @MockitoBean private JwtDecoder jwtDecoder;

    private final User currentUser = user("leonardo");
    private final User other = user("maria");
    private final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(currentUserService.resolve(any())).thenReturn(currentUser);
    }

    @Test
    void mutationsUseAuthenticatedUserAndExpectedStatuses() throws Exception {
        when(friendService.sendRequest(other.getId(), currentUser)).thenReturn(request(FriendRequestStatus.PENDING));
        when(friendService.acceptRequest(requestId, currentUser)).thenReturn(request(FriendRequestStatus.ACCEPTED));
        when(friendService.rejectRequest(requestId, currentUser)).thenReturn(request(FriendRequestStatus.REJECTED));

        mockMvc.perform(post("/api/v1/friends/requests/{id}", other.getId())
                        .param("currentUserId", other.getId().toString()).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender.id").value(currentUser.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.sender.keycloakId").doesNotExist());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", requestId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(post("/api/v1/friends/requests/{id}/reject", requestId).with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", requestId).with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/friends/{id}", other.getId()).with(jwt()))
                .andExpect(status().isNoContent());

        verify(friendService).sendRequest(other.getId(), currentUser);
        verify(friendService).acceptRequest(requestId, currentUser);
        verify(friendService).rejectRequest(requestId, currentUser);
        verify(friendService).cancelRequest(requestId, currentUser);
        verify(friendService).removeFriend(other.getId(), currentUser);
    }

    @Test
    void listsReturnPagedHalAndClampPageSize() throws Exception {
        var pageable = PageRequest.of(0, 100);
        var person = new FriendDiscoveryResponse(other.getId(), "maria", "Maria", "Silva", null,
                RelationshipStatus.REQUEST_RECEIVED, 2);
        when(friendService.discover("maria", pageable, currentUser))
                .thenReturn(new PageImpl<>(List.of(person), pageable, 1));
        when(friendService.getFriends(PageRequest.of(0, 20), currentUser))
                .thenReturn(new PageImpl<>(List.of(new FriendResponse(other.getId(), "maria", "Maria", "Silva", null, Instant.now()))));
        when(friendService.getReceivedRequests(PageRequest.of(0, 20), currentUser))
                .thenReturn(new PageImpl<>(List.of(request(FriendRequestStatus.PENDING))));
        when(friendService.getSentRequests(PageRequest.of(0, 20), currentUser))
                .thenReturn(new PageImpl<>(List.of(request(FriendRequestStatus.PENDING))));

        mockMvc.perform(get("/api/v1/friends/discover").param("search", "maria")
                        .param("page", "-1").param("size", "500").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.people[0].relationshipStatus").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$._embedded.people[0].mutualFriendsCount").value(2))
                .andExpect(jsonPath("$.page.size").value(100));
        mockMvc.perform(get("/api/v1/friends").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.friends[0].id").value(other.getId().toString()))
                .andExpect(jsonPath("$._embedded.friends[0].friendsSince").exists());
        for (String path : List.of("received", "sent")) {
            mockMvc.perform(get("/api/v1/friends/requests/" + path).with(jwt()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$._embedded.friendRequests[0].status").value("PENDING"));
        }
    }

    @Test
    void friendSearchUsesAuthenticatedUserAndPaginatesFilteredResults() throws Exception {
        var pageable = PageRequest.of(0, 24);
        when(friendService.searchFriends("Maria", pageable, currentUser))
                .thenReturn(new PageImpl<>(List.of(new FriendResponse(other.getId(), "maria", "Maria", "Silva", null, Instant.now())), pageable, 1));
        mockMvc.perform(get("/api/v1/friends").param("search", "Maria").param("size", "24").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.friends[0].id").value(other.getId().toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        verify(friendService).searchFriends("Maria", pageable, currentUser);
    }

    @Test
    void relationshipReturnsActionableRequestIdUsingAuthenticatedUser() throws Exception {
        when(friendService.getRelationship(other.getId(), currentUser))
                .thenReturn(new FriendRelationshipResponse(RelationshipStatus.REQUEST_RECEIVED, requestId));
        mockMvc.perform(get("/api/v1/friends/relationships/{id}", other.getId()).with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$.requestId").value(requestId.toString()));
        verify(friendService).getRelationship(other.getId(), currentUser);
    }

    @Test
    void domainErrorsKeepExistingProblemDetailContract() throws Exception {
        when(friendService.sendRequest(other.getId(), currentUser))
                .thenThrow(new BadRequestException("Self request"))
                .thenThrow(new ConflictException("Pending request exists"))
                .thenThrow(new NotFoundException("User not found"));
        for (int expectedStatus : new int[]{400, 409, 404}) {
            mockMvc.perform(post("/api/v1/friends/requests/{id}", other.getId()).with(jwt()))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(result -> assertThat(((ErrorResponseException) result.getResolvedException())
                            .getBody().getDetail()).isNotBlank());
        }
        doThrow(new ForbiddenException("Only sender can cancel"))
                .when(friendService).cancelRequest(requestId, currentUser);
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", requestId).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyEndpointRequiresJwt() throws Exception {
        for (String path : List.of("", "/discover", "/requests/received", "/requests/sent")) {
            mockMvc.perform(get("/api/v1/friends" + path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/friends/relationships/{id}", other.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/friends/requests/{id}", other.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/accept", requestId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/friends/requests/{id}/reject", requestId)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/friends/requests/{id}", requestId)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/friends/{id}", other.getId())).andExpect(status().isUnauthorized());
        verifyNoInteractions(friendService);
    }

    private FriendRequestResponse request(FriendRequestStatus status) {
        return new FriendRequestResponse(requestId,
                new UserSummaryResponse(currentUser.getId(), "leonardo", "Leonardo", null, null),
                new UserSummaryResponse(other.getId(), "maria", "Maria", "Silva", null),
                status, Instant.now(), status == FriendRequestStatus.PENDING ? null : Instant.now());
    }
}
