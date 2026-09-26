package com.naro.friend;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.exception.ConflictException;
import com.naro.friend.application.dto.FriendRequestResponse;
import com.naro.friend.application.service.FriendService;
import com.naro.friend.application.service.FriendServiceImpl;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.friend.domain.repository.FriendRequestRepository;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each service invocation starts and commits its own real PostgreSQL transaction. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestcontainersConfiguration.class, FriendServiceImpl.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FriendServiceIntegrationTest {

    @Autowired
    private FriendService service;

    @Autowired
    private FriendRequestRepository requests;

    @Autowired
    private FriendshipRepository friendships;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private User sender;
    private User receiver;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        sender = users.saveAndFlush(new User("kc-sender-" + suffix, "sender-" + suffix, null, "Sender", "Test"));
        receiver = users.saveAndFlush(new User("kc-receiver-" + suffix, "receiver-" + suffix, null, "Receiver", "Test"));
    }

    @Test
    void friendshipInsertionFailureRollsBackAlreadyFlushedRequestResponse() {
        UUID requestId = service.sendRequest(receiver.getId(), sender).id();
        // Force the INSERT itself to fail after the request UPDATE has been flushed.
        // This constraint affects only this test's pair and is removed even on failure.
        String constraint = "ck_test_friendship_insert_failure";
        jdbc.execute("""
                ALTER TABLE friendships ADD CONSTRAINT %s CHECK (
                    NOT ((user_a_id = '%s' AND user_b_id = '%s')
                      OR (user_a_id = '%s' AND user_b_id = '%s'))
                )
                """.formatted(constraint, sender.getId(), receiver.getId(), receiver.getId(), sender.getId()));
        try {
            assertThatThrownBy(() -> service.acceptRequest(requestId, receiver))
                    .isInstanceOf(ConflictException.class);

            var persisted = requests.findById(requestId).orElseThrow();
            assertThat(persisted.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
            assertThat(persisted.getRespondedAt()).isNull();
            assertThat(friendships.existsFriendshipBetweenUsers(sender.getId(), receiver.getId())).isFalse();
        } finally {
            jdbc.execute("ALTER TABLE friendships DROP CONSTRAINT " + constraint);
        }

        assertThat(service.acceptRequest(requestId, receiver).status()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(friendships.findFriendshipBetweenUsers(receiver.getId(), sender.getId())).isPresent();
    }

    @Test
    void concurrentOppositeRequestsCreateExactlyOnePendingRequestWithoutDeadlock() throws Exception {
        List<Outcome> outcomes = race(
                () -> service.sendRequest(receiver.getId(), sender),
                () -> service.sendRequest(sender.getId(), receiver)
        );

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SUCCEEDED, Outcome.CONFLICT);
        var pending = requests.findPendingRequestsAmong(sender.getId(), List.of(receiver.getId()));
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(friendships.existsFriendshipBetweenUsers(sender.getId(), receiver.getId())).isFalse();
    }

    @Test
    void concurrentAcceptsCreateExactlyOneFriendshipAndRespondOnlyOnce() throws Exception {
        UUID requestId = service.sendRequest(receiver.getId(), sender).id();

        List<Outcome> outcomes = race(
                () -> service.acceptRequest(requestId, receiver),
                () -> service.acceptRequest(requestId, receiver)
        );

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.SUCCEEDED, Outcome.CONFLICT);
        var persisted = requests.findById(requestId).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(persisted.getRespondedAt()).isNotNull();
        assertThat(friendships.findFriendshipsAmong(sender.getId(), List.of(receiver.getId()))).hasSize(1);
        assertThat(requests.findPendingRequestsAmong(sender.getId(), List.of(receiver.getId()))).isEmpty();
    }

    @Test
    void requestConcurrentWithAcceptanceCannotLeavePendingRequestBetweenFriends() throws Exception {
        UUID requestId = service.sendRequest(receiver.getId(), sender).id();

        List<Outcome> outcomes = race(
                () -> service.acceptRequest(requestId, receiver),
                () -> service.sendRequest(sender.getId(), receiver)
        );

        assertThat(outcomes).containsExactly(Outcome.SUCCEEDED, Outcome.CONFLICT);
        assertThat(requests.findById(requestId)).get()
                .extracting(request -> request.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(friendships.findFriendshipsAmong(sender.getId(), List.of(receiver.getId()))).hasSize(1);
        assertThat(requests.findPendingRequestsAmong(sender.getId(), List.of(receiver.getId()))).isEmpty();
    }

    @Test
    void acceptedFriendshipIsVisibleInBothDirectionsAndRemovalPreservesUsersAndHistory() {
        UUID requestId = service.sendRequest(receiver.getId(), sender).id();
        service.acceptRequest(requestId, receiver);

        assertThat(service.getFriends(PageRequest.of(0, 10), sender).getContent())
                .extracting(friend -> friend.id()).containsExactly(receiver.getId());
        assertThat(service.getFriends(PageRequest.of(0, 10), receiver).getContent())
                .extracting(friend -> friend.id()).containsExactly(sender.getId());
        service.removeFriend(sender.getId(), receiver);

        assertThat(friendships.existsFriendshipBetweenUsers(sender.getId(), receiver.getId())).isFalse();
        assertThat(users.findById(sender.getId())).isPresent();
        assertThat(users.findById(receiver.getId())).isPresent();
        assertThat(requests.findById(requestId)).get()
                .extracting(request -> request.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(service.sendRequest(sender.getId(), receiver).status()).isEqualTo(FriendRequestStatus.PENDING);
    }

    private static List<Outcome> race(Callable<FriendRequestResponse> first,
                                      Callable<FriendRequestResponse> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Future<Outcome> firstResult = executor.submit(() -> invokeWhenReleased(first, ready, start));
        Future<Outcome> secondResult = executor.submit(() -> invokeWhenReleased(second, ready, start));
        try {
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("both operations must be ready").isTrue();
            start.countDown();
            // Deadlock, unexpected database failure or missing transaction fails the test.
            return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            firstResult.cancel(true);
            secondResult.cancel(true);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("workers must finish").isTrue();
        }
    }

    private static Outcome invokeWhenReleased(Callable<FriendRequestResponse> operation,
                                              CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent operations were not released");
        }
        try {
            operation.call();
            return Outcome.SUCCEEDED;
        } catch (ConflictException expected) {
            return Outcome.CONFLICT;
        }
    }

    private enum Outcome {
        SUCCEEDED,
        CONFLICT
    }
}
