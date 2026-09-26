package com.naro.user.application.service;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.security.UserProvisioningFilter;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, CurrentUserService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CurrentUserServiceIntegrationTest {

    @Autowired private CurrentUserService currentUsers;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void concurrentFirstRequestsProvisionOneAccountAndReturnItsSameInternalId() throws Exception {
        String subject = UUID.randomUUID().toString();
        String username = "new-account-" + UUID.randomUUID().toString().substring(0, 8);
        Jwt token = Jwt.withTokenValue("test-access-token").header("alg", "RS256")
                .subject(subject).claim("preferred_username", username)
                .claim("email", username + "@example.com")
                .claim("given_name", "New").claim("family_name", "Account").build();
        assertThat(users.findByKeycloakId(subject)).isEmpty();
        int workers = 6;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        List<Future<User>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                pending.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Provisioning race was not released");
                    }
                    // Each invocation gets its own Spring-managed transaction.
                    return currentUsers.resolve(token);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<User> resolved = new ArrayList<>();
            for (var future : pending) {
                resolved.add(future.get(20, TimeUnit.SECONDS));
            }
            User persisted = users.findByKeycloakId(subject).orElseThrow();
            assertThat(resolved).extracting(User::getId).containsOnly(persisted.getId());
            assertThat(users.findAll()).filteredOn(user -> subject.equals(user.getKeycloakId())).hasSize(1);
            assertThat(persisted.getUsername()).isEqualTo(username);
            assertThat(persisted.getEmail()).isEqualTo(username + "@example.com");
            assertThat(persisted.getFirstName()).isEqualTo("New");
            assertThat(persisted.getLastName()).isEqualTo("Account");
            assertThat(persisted.getCreatedAt()).isNotNull();
            assertThat(persisted.getUpdatedAt()).isEqualTo(persisted.getCreatedAt());
            assertThat(currentUsers.resolve(token).getId()).isEqualTo(persisted.getId());
        } finally {
            start.countDown();
            for (var future : pending) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentInsertsAfterAllTransactionsObservedAbsenceHandleEveryUniqueIndex() throws Exception {
        // Force the insert path in every transaction, rather than allowing later
        // service calls to take their fast existing-account path.
        String subject = UUID.randomUUID().toString();
        String username = "forced-race-" + UUID.randomUUID().toString().substring(0, 8);
        int workers = 6;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        var tx = new TransactionTemplate(transactions);
        List<Future<ProvisionResult>> pending = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                pending.add(executor.submit(() -> tx.execute(status -> {
                    assertThat(users.findByKeycloakId(subject)).isEmpty();
                    ready.countDown();
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent inserts were not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                    int inserted = users.provisionIfAbsent(subject, username, null, null, null);
                    User resolved = users.findByKeycloakId(subject).orElseThrow();
                    return new ProvisionResult(inserted, resolved.getId());
                })));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ProvisionResult> results = new ArrayList<>();
            for (var future : pending) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results.stream().mapToInt(ProvisionResult::inserted).sum()).isEqualTo(1);
            assertThat(results).extracting(ProvisionResult::userId)
                    .containsOnly(users.findByKeycloakId(subject).orElseThrow().getId());
        } finally {
            start.countDown();
            for (var future : pending) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void usernameCollisionCannotLinkAnotherSubjectAndFilterReturnsUnauthorized() throws Exception {
        String username = "reserved-name-" + UUID.randomUUID().toString().substring(0, 8);
        String ownerSubject = UUID.randomUUID().toString();
        Jwt ownerToken = Jwt.withTokenValue("owner").header("alg", "RS256").subject(ownerSubject)
                .claim("preferred_username", username).build();
        User owner = currentUsers.resolve(ownerToken);
        String otherSubject = UUID.randomUUID().toString();
        Jwt otherToken = Jwt.withTokenValue("other").header("alg", "RS256").subject(otherSubject)
                .claim("preferred_username", username).build();

        assertThatThrownBy(() -> currentUsers.resolve(otherToken)).isInstanceOf(BadCredentialsException.class);
        assertThat(users.findByKeycloakId(otherSubject)).isEmpty();
        assertThat(users.findByKeycloakId(ownerSubject).orElseThrow().getId()).isEqualTo(owner.getId());

        var request = new MockHttpServletRequest("GET", "/api/v1/friends");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        try {
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(otherToken));
            new UserProvisioningFilter(currentUsers).doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(chain.getRequest()).isNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(currentUsers.resolve(ownerToken).getId()).isEqualTo(owner.getId());
    }

    private record ProvisionResult(int inserted, UUID userId) {}

    @Test
    void existingAccountRetainsItsProfileWhenLaterTokenClaimsChange() {
        String subject = UUID.randomUUID().toString();
        String username = "original-" + UUID.randomUUID().toString().substring(0, 8);
        Jwt original = Jwt.withTokenValue("original").header("alg", "RS256").subject(subject)
                .claim("preferred_username", username).claim("given_name", "Original").build();
        User persisted = currentUsers.resolve(original);
        Jwt changed = Jwt.withTokenValue("changed").header("alg", "RS256").subject(subject)
                .claim("preferred_username", "changed-" + username).claim("given_name", "Changed").build();

        User resolved = currentUsers.resolve(changed);
        assertThat(resolved.getId()).isEqualTo(persisted.getId());
        assertThat(resolved.getUsername()).isEqualTo(username);
        assertThat(resolved.getFirstName()).isEqualTo("Original");
        assertThat(resolved.getEmail()).isNull();
        assertThat(resolved.getLastName()).isNull();
    }
}
