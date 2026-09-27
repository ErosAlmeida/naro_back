package com.naro.friend.domain.repository;

import com.naro.PostgresTestcontainersConfiguration;
import com.naro.friend.domain.entity.FriendRequest;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class FriendRepositoryTest {

    @Autowired
    private FriendRequestRepository requests;

    @Autowired
    private FriendshipRepository friendships;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager entityManager;

    private User current;
    private User incoming;
    private User outgoing;
    private User other;

    @BeforeEach
    void setUp() {
        current = user("current");
        incoming = user("incoming");
        outgoing = user("outgoing");
        other = user("other");
    }

    @Test
    void pendingQueriesFilterHistoryAndLoadBothUsersInEitherDirection() {
        FriendRequest received = requests.saveAndFlush(FriendRequest.between(incoming, current));
        FriendRequest sent = requests.saveAndFlush(FriendRequest.between(current, outgoing));
        received.respond(FriendRequestStatus.REJECTED);
        requests.flush();
        FriendRequest rejected = requests.saveAndFlush(FriendRequest.between(current, incoming));
        rejected.respond(FriendRequestStatus.CANCELED);
        requests.flush();
        received = requests.saveAndFlush(FriendRequest.between(incoming, current));
        requests.saveAndFlush(FriendRequest.between(incoming, other));
        UUID receivedId = received.getId();
        UUID sentId = sent.getId();
        UUID currentId = current.getId();
        UUID incomingId = incoming.getId();
        UUID outgoingId = outgoing.getId();
        entityManager.clear();

        assertThat(requests.findPendingRequestBetweenUsers(currentId, incomingId)).get()
                .extracting(FriendRequest::getId).isEqualTo(receivedId);
        assertThat(requests.findPendingRequestBetweenUsers(incomingId, currentId)).get()
                .extracting(FriendRequest::getId).isEqualTo(receivedId);

        var receivedPage = requests.findReceivedPendingRequests(currentId, PageRequest.of(0, 1));
        assertThat(receivedPage.getTotalElements()).isEqualTo(1);
        assertThat(receivedPage.getContent()).extracting(FriendRequest::getId).containsExactly(receivedId);
        assertThat(Hibernate.isInitialized(receivedPage.getContent().getFirst().getSender())).isTrue();
        assertThat(Hibernate.isInitialized(receivedPage.getContent().getFirst().getReceiver())).isTrue();
        assertThat(requests.findSentPendingRequests(currentId, PageRequest.of(0, 10)).getContent())
                .extracting(FriendRequest::getId).containsExactly(sentId);
        assertThat(requests.findPendingRequestsAmong(currentId, List.of(incomingId, outgoingId)))
                .extracting(FriendRequest::getId).containsExactlyInAnyOrder(receivedId, sentId);
        assertThat(requests.findByIdForUpdate(receivedId)).isPresent();
        assertThat(users.findByIdForUpdate(currentId)).isPresent();
        assertThat(requests.count()).isEqualTo(5);
    }

    @Test
    void friendshipQueriesAreBidirectionalAndDeletingFriendshipKeepsUsersAndHistory() {
        Friendship forward = friendships.saveAndFlush(Friendship.between(current, incoming));
        Friendship reverse = friendships.saveAndFlush(Friendship.between(outgoing, current));
        friendships.saveAndFlush(Friendship.between(incoming, other));
        FriendRequest accepted = requests.saveAndFlush(FriendRequest.between(current, incoming));
        accepted.respond(FriendRequestStatus.ACCEPTED);
        requests.flush();
        UUID forwardId = forward.getId();
        UUID reverseId = reverse.getId();
        UUID currentId = current.getId();
        UUID incomingId = incoming.getId();
        UUID outgoingId = outgoing.getId();
        entityManager.clear();

        assertThat(friendships.existsFriendshipBetweenUsers(currentId, incomingId)).isTrue();
        assertThat(friendships.existsFriendshipBetweenUsers(incomingId, currentId)).isTrue();
        assertThat(friendships.existsFriendshipBetweenUsers(currentId, other.getId())).isFalse();
        assertThat(friendships.findFriendshipBetweenUsers(incomingId, currentId)).get()
                .extracting(Friendship::getId).isEqualTo(forwardId);
        assertThat(friendships.findFriendsByUser(currentId, PageRequest.of(0, 1)).getTotalElements()).isEqualTo(2);
        var friends = friendships.findFriendsByUser(currentId, PageRequest.of(0, 10)).getContent();
        assertThat(friends).extracting(Friendship::getId).containsExactly(reverseId, forwardId);
        assertThat(Hibernate.isInitialized(friends.getFirst().getUserA())).isTrue();
        assertThat(Hibernate.isInitialized(friends.getFirst().getUserB())).isTrue();
        assertThat(friendships.findFriendsByUser(incomingId, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(friendships.findFriendshipsAmong(currentId, List.of(incomingId, outgoingId)))
                .extracting(Friendship::getId).containsExactlyInAnyOrder(forwardId, reverseId);

        friendships.delete(friendships.findFriendshipBetweenUsers(incomingId, currentId).orElseThrow());
        friendships.flush();
        entityManager.clear();
        assertThat(friendships.existsFriendshipBetweenUsers(currentId, incomingId)).isFalse();
        assertThat(users.count()).isEqualTo(4);
        assertThat(users.findById(currentId)).isPresent();
        assertThat(users.findById(incomingId)).isPresent();
        assertThat(requests.findById(accepted.getId())).get()
                .extracting(FriendRequest::getStatus).isEqualTo(FriendRequestStatus.ACCEPTED);
    }

    /* Leonardo searches "mari": manual search is global. */
    @Test
    void usernameSearchIsGlobalRanksExactThenPrefixAndReportsEachRelationship() {
        User leonardo = user("leonardo");
        User maria = user("maria");
        User mariana = user("mariana");
        User amariz = user("amariz");
        User marisa = user("marisa");
        User marilia = user("marilia");
        entityManager.persistAndFlush(new User("kc-name-only", "ana-souza", null, "Mariana", "Souza"));
        entityManager.persistAndFlush(new User("seed:mari", "mari-fixture", null, null, null));
        friendships.saveAndFlush(Friendship.between(amariz, leonardo));
        friendships.saveAndFlush(Friendship.between(amariz, mariana));
        requests.saveAndFlush(FriendRequest.between(leonardo, marisa));
        requests.saveAndFlush(FriendRequest.between(marilia, leonardo));
        entityManager.clear();

        var page = friendships.searchByUsernameOrEmail("mari", "%mari%", leonardo.getId(), PageRequest.of(0, 20));

        // Username only (not first name), no artificial accounts, prefix before contains.
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getContent()).extracting(FriendDiscoveryCandidate::getUsername)
                .containsExactly("mariana", "maria", "marilia", "marisa", "amariz");
        assertThat(page.getContent()).extracting(FriendDiscoveryCandidate::getRelationshipStatus)
                .containsExactly("NONE", "NONE", "REQUEST_RECEIVED", "REQUEST_SENT", "FRIEND");
        assertThat(page.getContent()).extracting(FriendDiscoveryCandidate::getMutualFriendsCount)
                .containsExactly(1L, 0L, 0L, 0L, 0L);

        // The exact username wins over a prefix match with more mutual friends.
        assertThat(friendships.searchByUsernameOrEmail("maria", "%maria%", leonardo.getId(), PageRequest.of(0, 20))
                .getContent()).extracting(FriendDiscoveryCandidate::getId).containsExactly(maria.getId(), mariana.getId());
        var paged = friendships.searchByUsernameOrEmail("mari", "%mari%", leonardo.getId(), PageRequest.of(2, 2));
        assertThat(paged.getTotalElements()).isEqualTo(5);
        assertThat(paged.getContent()).extracting(FriendDiscoveryCandidate::getId).containsExactly(amariz.getId());
        assertThat(friendships.searchByUsernameOrEmail("leonardo", "%leonardo%", leonardo.getId(), PageRequest.of(0, 20)))
                .isEmpty();
    }

    /* The email only finds someone when it is typed in full; it is never returned. */
    @Test
    void emailSearchMatchesOnlyTheWholeAddressIgnoringCase() {
        User leonardo = user("leonardo");
        User maria = entityManager.persistAndFlush(new User("kc-maria", "maria", "Maria.Silva@Naro.local", "Maria", "Silva"));
        entityManager.persistAndFlush(new User("seed:fixture", "fixture", "fixture@naro.local", null, null));

        assertThat(friendships.searchByUsernameOrEmail("maria.silva@naro.local", "%maria.silva@naro.local%",
                leonardo.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(FriendDiscoveryCandidate::getId).containsExactly(maria.getId());
        // Part of an address, the domain or an artificial account's address finds nobody.
        for (String term : List.of("maria.silva@naro", "silva@naro.local", "naro.local", "fixture@naro.local")) {
            var page = friendships.searchByUsernameOrEmail(term, "%" + term + "%", leonardo.getId(), PageRequest.of(0, 20));
            assertThat(page.getContent()).as(term).isEmpty();
            assertThat(page.getTotalElements()).as(term).isZero();
        }
        // Nobody finds themselves by their own email.
        assertThat(friendships.searchByUsernameOrEmail("maria.silva@naro.local", "%maria.silva@naro.local%",
                maria.getId(), PageRequest.of(0, 20))).isEmpty();
    }

    @Test
    void usernameSearchTreatsLikeWildcardsAsLiteralText() {
        User wildcard = user("percent%_user");
        user("percent-any-user");

        assertThat(friendships.searchByUsernameOrEmail("percent%_user", "%percent\\%\\_user%", current.getId(),
                PageRequest.of(0, 10)).getContent())
                .extracting(FriendDiscoveryCandidate::getId).containsExactly(wildcard.getId());
    }

    @Test
    void friendSearchFiltersBeforePaginationAndOnlyReturnsAcceptedRealFriendsInEitherDirection() {
        User nameMatch = entityManager.persistAndFlush(new User("kc-maria", "maria", null, "Alice", "Silva"));
        User usernameMatch = user("alice%_handle");
        User pending = user("alice-pending");
        User stranger = user("alice-stranger");
        User fixture = entityManager.persistAndFlush(new User("seed:alice", "alice-fixture", null, "Alice", "Test"));
        friendships.saveAndFlush(Friendship.between(nameMatch, current));
        friendships.saveAndFlush(Friendship.between(current, usernameMatch));
        friendships.saveAndFlush(Friendship.between(current, fixture));
        requests.saveAndFlush(FriendRequest.between(current, pending));
        var first = friendships.searchFriendsByUser(current.getId(), "%alice%", PageRequest.of(0, 1));
        var second = friendships.searchFriendsByUser(current.getId(), "%alice%", PageRequest.of(1, 1));
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(List.of(first.getContent().getFirst().getId(), second.getContent().getFirst().getId()))
                .containsExactlyInAnyOrder(
                        friendships.findFriendshipBetweenUsers(current.getId(), nameMatch.getId()).orElseThrow().getId(),
                        friendships.findFriendshipBetweenUsers(current.getId(), usernameMatch.getId()).orElseThrow().getId());
        assertThat(friendships.searchFriendsByUser(current.getId(), "%alice\\%\\_handle%", PageRequest.of(0, 10)))
                .extracting(f -> f.getUserB().getId()).containsExactly(usernameMatch.getId());
        assertThat(friendships.searchFriendsByUser(current.getId(), "%alice silva%", PageRequest.of(0, 10)))
                .hasSize(1);
        assertThat(friendships.findFriendsByUser(current.getId(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(users.findById(pending.getId())).isPresent();
        assertThat(users.findById(stranger.getId())).isPresent();
    }

    private User user(String username) {
        return entityManager.persistAndFlush(new User("kc-" + username, username, username + "@naro.local", username, "Test"));
    }
}
