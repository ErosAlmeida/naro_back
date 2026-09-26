package com.naro.friend;

import com.jayway.jsonpath.JsonPath;
import com.naro.MinioTestcontainersConfiguration;
import com.naro.PostgresTestcontainersConfiguration;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.friend.domain.repository.FriendRequestRepository;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JWT identity → provisioning → controller → real service → PostgreSQL. */
@SpringBootTest(properties = "app.seed.enabled=false")
@AutoConfigureMockMvc
@Import({PostgresTestcontainersConfiguration.class, MinioTestcontainersConfiguration.class})
@Testcontainers(disabledWithoutDocker = true)
class FriendEndpointsIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private FriendRequestRepository requests;
    @Autowired private FriendshipRepository friendships;

    @Test
    void twoNewAccountsCanDiscoverSendAcceptAndRemoveWithoutSeedOrChannel() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String firstSubject = UUID.randomUUID().toString();
        String secondSubject = UUID.randomUUID().toString();
        String firstUsername = "real-first-" + suffix;
        String secondUsername = "real-second-" + suffix;
        var first = as(firstSubject, firstUsername);
        var second = as(secondSubject, secondUsername);
        assertThat(users.findByKeycloakId(firstSubject)).isEmpty();
        assertThat(users.findByKeycloakId(secondSubject)).isEmpty();

        // Actual provisioning from a JWT, no repository-created mock account.
        UUID firstId = me(first);
        UUID secondId = me(second);
        assertThat(firstId).isNotEqualTo(secondId);
        mvc.perform(get("/api/v1/friends/discover").with(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.people[?(@.id == '" + secondId + "')].relationshipStatus").value(org.hamcrest.Matchers.contains("NONE")))
                .andExpect(jsonPath("$._embedded.people[?(@.id == '" + firstId + "')]").isEmpty());
        mvc.perform(get("/api/v1/friends/discover").with(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.people[?(@.id == '" + firstId + "')].relationshipStatus").value(org.hamcrest.Matchers.contains("NONE")));

        String body = mvc.perform(post("/api/v1/friends/requests/{id}", secondId).with(first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sender.id").value(firstId.toString()))
                .andExpect(jsonPath("$.receiver.id").value(secondId.toString()))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(JsonPath.read(body, "$.id"));
        mvc.perform(get("/api/v1/friends/requests/received").with(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.friendRequests[0].id").value(requestId.toString()));
        relationship(secondId, first, "REQUEST_SENT", requestId);
        relationship(firstId, second, "REQUEST_RECEIVED", requestId);
        mvc.perform(get("/api/v1/friends/discover").param("search", secondUsername).with(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.people[0].relationshipStatus").value("REQUEST_SENT"));
        mvc.perform(get("/api/v1/friends/discover").with(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$._embedded").doesNotExist());
        mvc.perform(post("/api/v1/friends/requests/{id}/accept", requestId).with(first))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/friends/requests/{id}/accept", requestId).with(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/friends").with(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.friends[0].id").value(secondId.toString()));
        mvc.perform(get("/api/v1/friends").with(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.friends[0].id").value(firstId.toString()));
        relationship(secondId, first, "FRIEND", null);
        relationship(firstId, second, "FRIEND", null);
        mvc.perform(delete("/api/v1/friends/{id}", firstId).with(second))
                .andExpect(status().isNoContent());
        assertThat(friendships.existsFriendshipBetweenUsers(firstId, secondId)).isFalse();
        assertThat(users.findById(firstId)).isPresent();
        assertThat(users.findById(secondId)).isPresent();
        assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        relationship(secondId, first, "NONE", null);
        mvc.perform(get("/api/v1/friends/discover").param("search", secondUsername).with(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.people[0].relationshipStatus").value("NONE"));

        // The same real pair can cancel and reject later requests; no fixture
        // resets their state on subsequent authenticated calls.
        body = mvc.perform(post("/api/v1/friends/requests/{id}", secondId).with(first))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID canceledId = UUID.fromString(JsonPath.read(body, "$.id"));
        mvc.perform(delete("/api/v1/friends/requests/{id}", canceledId).with(first))
                .andExpect(status().isNoContent());
        relationship(secondId, first, "NONE", null);
        body = mvc.perform(post("/api/v1/friends/requests/{id}", firstId).with(second))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID rejectedId = UUID.fromString(JsonPath.read(body, "$.id"));
        mvc.perform(post("/api/v1/friends/requests/{id}/reject", rejectedId).with(first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        relationship(firstId, second, "NONE", null);
    }

    private UUID me(RequestPostProcessor identity) throws Exception {
        String body = mvc.perform(get("/api/users/me").with(identity)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private void relationship(UUID targetId, RequestPostProcessor identity, String status, UUID requestId) throws Exception {
        var result = mvc.perform(get("/api/v1/friends/relationships/{id}", targetId).with(identity))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(status));
        if (requestId == null) {
            result.andExpect(jsonPath("$.requestId").doesNotExist());
        } else {
            result.andExpect(jsonPath("$.requestId").value(requestId.toString()));
        }
    }

    private static RequestPostProcessor as(String subject, String username) {
        return jwt().jwt(token -> token.subject(subject)
                .claim("preferred_username", username)
                .claim("email", username + "@example.com")
                .claim("email_verified", true)
                .claim("given_name", username));
    }
}
