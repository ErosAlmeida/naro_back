package com.naro.friend.application.service;

import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.friend.domain.entity.FriendRequest;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.friend.domain.enums.RelationshipStatus;
import com.naro.friend.domain.repository.FriendRequestRepository;
import com.naro.friend.domain.repository.FriendDiscoveryCandidate;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.naro.support.Fixtures.persisted;
import static com.naro.support.Fixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendServiceImplTest {

    @Mock private FriendRequestRepository requestRepository;
    @Mock private FriendshipRepository friendshipRepository;
    @Mock private UserRepository userRepository;

    private FriendServiceImpl service;

    private final User sender = user("sender");
    private final User receiver = user("receiver");
    private final User outsider = user("outsider");

    @BeforeEach
    void setUp() {
        // Put the receiver first in the lock order, regardless of request direction.
        ReflectionTestUtils.setField(sender, "id", UUID.fromString("90000000-0000-0000-0000-000000000000"));
        ReflectionTestUtils.setField(receiver, "id", UUID.fromString("10000000-0000-0000-0000-000000000000"));
        service = new FriendServiceImpl(requestRepository, friendshipRepository, userRepository);
    }

    @Test
    void sendRequestSavesTheAuthenticatedSenderAndTargetAsPending() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        when(requestRepository.saveAndFlush(any(FriendRequest.class)))
                .thenAnswer(call -> persisted(call.getArgument(0)));

        var response = service.sendRequest(receiver.getId(), sender);

        var saved = ArgumentCaptor.forClass(FriendRequest.class);
        verify(requestRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSender()).isSameAs(sender);
        assertThat(saved.getValue().getReceiver()).isSameAs(receiver);
        assertThat(saved.getValue().getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(saved.getValue().getRespondedAt()).isNull();
        assertThat(response.id()).isEqualTo(saved.getValue().getId());
        assertThat(response.sender().id()).isEqualTo(sender.getId());
        assertThat(response.receiver().id()).isEqualTo(receiver.getId());
        assertThat(response.status()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.respondedAt()).isNull();
        verify(friendshipRepository, never()).saveAndFlush(any());
    }

    @Test
    void sendRequestLocksUsersInTheSameOrderForEitherRequestDirection() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        when(userRepository.getReferenceById(sender.getId())).thenReturn(sender);
        when(requestRepository.saveAndFlush(any(FriendRequest.class)))
                .thenAnswer(call -> persisted(call.getArgument(0)));

        service.sendRequest(receiver.getId(), sender);
        service.sendRequest(sender.getId(), receiver);

        var ordered = inOrder(userRepository);
        ordered.verify(userRepository).findByIdForUpdate(receiver.getId());
        ordered.verify(userRepository).findByIdForUpdate(sender.getId());
        ordered.verify(userRepository).findByIdForUpdate(receiver.getId());
        ordered.verify(userRepository).findByIdForUpdate(sender.getId());
    }

    @Test
    void userCannotSendRequestToThemself() {
        assertThatThrownBy(() -> service.sendRequest(sender.getId(), sender))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, requestRepository, friendshipRepository);
    }

    @Test
    void requestToMissingUserReturnsNotFound() {
        when(userRepository.findByIdForUpdate(receiver.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(requestRepository, friendshipRepository);
    }

    @Test
    void existingFriendshipPreventsSendingAnotherRequest() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        when(friendshipRepository.existsFriendshipBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void pendingRequestPreventsAnotherRequestInTheSameDirection() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        when(requestRepository.findPendingRequestBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(Optional.of(persisted(FriendRequest.between(sender, receiver))));

        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void pendingRequestPreventsAnOppositeRequestWithoutAutomaticallyAcceptingIt() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        FriendRequest received = persisted(FriendRequest.between(receiver, sender));
        when(requestRepository.findPendingRequestBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(Optional.of(received));

        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender))
                .isInstanceOf(ConflictException.class);

        assertThat(received.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(requestRepository, never()).saveAndFlush(any());
        verify(friendshipRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentPendingRequestConstraintFailureBecomesConflict() {
        lockUsers();
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        when(requestRepository.saveAndFlush(any(FriendRequest.class)))
                .thenThrow(new DataIntegrityViolationException("uk_friend_requests_pending_pair"));

        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender))
                .isInstanceOf(ConflictException.class);

        verify(friendshipRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptingRequestCreatesOneFriendshipAndKeepsTheAcceptedRequestHistory() {
        FriendRequest request = pendingRequest();
        lockUsers();
        when(friendshipRepository.saveAndFlush(any(Friendship.class)))
                .thenAnswer(call -> persisted(call.getArgument(0)));
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        var response = service.acceptRequest(request.getId(), receiver);

        var saved = ArgumentCaptor.forClass(Friendship.class);
        verify(friendshipRepository).saveAndFlush(saved.capture());
        assertThat(List.of(saved.getValue().getUserA(), saved.getValue().getUserB()))
                .containsExactlyInAnyOrder(sender, receiver);
        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(request.getRespondedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(response.respondedAt()).isEqualTo(request.getRespondedAt());
        verify(requestRepository).saveAndFlush(request);
        verify(requestRepository, never()).delete(any());
        var ordered = inOrder(requestRepository, userRepository);
        ordered.verify(requestRepository).findByIdForUpdate(request.getId());
        ordered.verify(userRepository).findByIdForUpdate(receiver.getId());
        ordered.verify(userRepository).findByIdForUpdate(sender.getId());
    }

    @Test
    void senderCannotAcceptTheirOwnRequest() {
        FriendRequest request = pendingRequest();

        assertThatThrownBy(() -> service.acceptRequest(request.getId(), sender))
                .isInstanceOf(ForbiddenException.class);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verifyNoInteractions(userRepository, friendshipRepository);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void outsiderCannotAcceptAnotherUsersRequest() {
        FriendRequest request = pendingRequest();

        assertThatThrownBy(() -> service.acceptRequest(request.getId(), outsider))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(userRepository, friendshipRepository);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = FriendRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void alreadyRespondedRequestCannotBeAccepted(FriendRequestStatus status) {
        FriendRequest request = pendingRequest();
        request.respond(status);

        assertThatThrownBy(() -> service.acceptRequest(request.getId(), receiver))
                .isInstanceOf(ConflictException.class);

        assertThat(request.getStatus()).isEqualTo(status);
        verifyNoInteractions(userRepository, friendshipRepository);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void acceptingRequestBetweenExistingFriendsFailsWithoutChangingItsStatus() {
        FriendRequest request = pendingRequest();
        lockUsers();
        when(friendshipRepository.existsFriendshipBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.acceptRequest(request.getId(), receiver))
                .isInstanceOf(ConflictException.class);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(requestRepository, never()).saveAndFlush(any());
        verify(friendshipRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentFriendshipConstraintFailureBecomesConflict() {
        FriendRequest request = pendingRequest();
        lockUsers();
        when(friendshipRepository.saveAndFlush(any(Friendship.class)))
                .thenThrow(new DataIntegrityViolationException("uk_friendships_pair"));

        assertThatThrownBy(() -> service.acceptRequest(request.getId(), receiver))
                .isInstanceOf(ConflictException.class);

        verify(requestRepository, never()).delete(any());
    }

    @Test
    void rejectingRequestRecordsAResponseAndDoesNotCreateFriendship() {
        FriendRequest request = pendingRequest();
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        var response = service.rejectRequest(request.getId(), receiver);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(request.getRespondedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(FriendRequestStatus.REJECTED);
        assertThat(response.respondedAt()).isEqualTo(request.getRespondedAt());
        verify(requestRepository).saveAndFlush(request);
        verify(requestRepository, never()).delete(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @Test
    void senderAndOutsiderCannotRejectRequest() {
        FriendRequest request = pendingRequest();

        assertThatThrownBy(() -> service.rejectRequest(request.getId(), sender))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rejectRequest(request.getId(), outsider))
                .isInstanceOf(ForbiddenException.class);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(requestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @ParameterizedTest
    @EnumSource(value = FriendRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void alreadyRespondedRequestCannotBeRejected(FriendRequestStatus status) {
        FriendRequest request = pendingRequest();
        request.respond(status);

        assertThatThrownBy(() -> service.rejectRequest(request.getId(), receiver))
                .isInstanceOf(ConflictException.class);

        assertThat(request.getStatus()).isEqualTo(status);
        verify(requestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @Test
    void cancelingRequestPreservesHistoryWithCanceledStatus() {
        FriendRequest request = pendingRequest();
        when(requestRepository.saveAndFlush(request)).thenReturn(request);

        service.cancelRequest(request.getId(), sender);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.CANCELED);
        assertThat(request.getRespondedAt()).isNotNull();
        verify(requestRepository).saveAndFlush(request);
        verify(requestRepository, never()).delete(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @Test
    void receiverAndOutsiderCannotCancelRequest() {
        FriendRequest request = pendingRequest();

        assertThatThrownBy(() -> service.cancelRequest(request.getId(), receiver))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.cancelRequest(request.getId(), outsider))
                .isInstanceOf(ForbiddenException.class);

        assertThat(request.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        verify(requestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @ParameterizedTest
    @EnumSource(value = FriendRequestStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    void alreadyRespondedRequestCannotBeCanceled(FriendRequestStatus status) {
        FriendRequest request = pendingRequest();
        request.respond(status);

        assertThatThrownBy(() -> service.cancelRequest(request.getId(), sender))
                .isInstanceOf(ConflictException.class);

        assertThat(request.getStatus()).isEqualTo(status);
        verify(requestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @Test
    void respondingToMissingRequestReturnsNotFoundForAllActions() {
        UUID requestId = UUID.randomUUID();
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptRequest(requestId, receiver))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.rejectRequest(requestId, receiver))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.cancelRequest(requestId, sender))
                .isInstanceOf(NotFoundException.class);

        verify(requestRepository, never()).saveAndFlush(any());
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    @Test
    void removingFriendDeletesOnlyTheFriendshipForEitherParticipant() {
        lockUsers();
        Friendship friendship = persisted(Friendship.between(sender, receiver));
        when(friendshipRepository.findFriendshipBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(Optional.of(friendship));
        when(friendshipRepository.findFriendshipBetweenUsers(receiver.getId(), sender.getId()))
                .thenReturn(Optional.of(friendship));

        service.removeFriend(receiver.getId(), sender);
        service.removeFriend(sender.getId(), receiver);

        verify(friendshipRepository, times(2)).delete(friendship);
        verify(userRepository, never()).delete(any());
        verify(userRepository, never()).deleteById(any());
        verifyNoInteractions(requestRepository);
    }

    @Test
    void removingNonexistentFriendshipReturnsNotFound() {
        lockUsers();

        assertThatThrownBy(() -> service.removeFriend(receiver.getId(), sender))
                .isInstanceOf(NotFoundException.class);

        verify(friendshipRepository, never()).delete(any());
        verify(userRepository, never()).delete(any());
        verifyNoInteractions(requestRepository);
    }

    @Test
    void userCannotRemoveThemselfAsFriend() {
        assertThatThrownBy(() -> service.removeFriend(sender.getId(), sender))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, requestRepository, friendshipRepository);
    }

    @Test
    void discoveryMapsRealPeopleStatusAndMutualCountsFromOneQuery() {
        receiver.updateProfile("Maria", "Silva", null, "https://naro.dev/maria.png");
        var pageable = PageRequest.of(0, 2);
        var people = List.of(candidate(receiver, RelationshipStatus.FRIEND, 3),
                candidate(outsider, RelationshipStatus.REQUEST_RECEIVED, 2));
        when(friendshipRepository.searchByUsername("ma", "%ma%", sender.getId(), pageable))
                .thenReturn(new PageImpl<>(people, pageable, 4));

        var response = service.discover("  MA  ", pageable, sender);

        assertThat(response.getContent()).extracting(person -> person.relationshipStatus())
                .containsExactly(RelationshipStatus.FRIEND, RelationshipStatus.REQUEST_RECEIVED);
        assertThat(response.getContent()).extracting(person -> person.mutualFriendsCount()).containsExactly(3L, 2L);
        assertThat(response.getContent().getFirst()).satisfies(person -> {
            assertThat(person.id()).isEqualTo(receiver.getId());
            assertThat(person.username()).isEqualTo(receiver.getUsername());
            assertThat(person.firstName()).isEqualTo("Maria");
            assertThat(person.lastName()).isEqualTo("Silva");
            assertThat(person.avatarUrl()).isEqualTo("https://naro.dev/maria.png");
        });
        assertThat(response.getTotalElements()).isEqualTo(4);
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void emptyDiscoveryNeverListsThePlatformAndFallsBackToFriendsOfFriends() {
        var pageable = PageRequest.of(0, 10);
        when(friendshipRepository.findRecommendations(sender.getId(), pageable))
                .thenReturn(Page.empty(pageable));

        assertThat(service.discover(null, pageable, sender)).isEmpty();
        assertThat(service.discover("   ", pageable, sender)).isEmpty();
        assertThat(service.discover(" @ ", pageable, sender)).isEmpty();

        verify(friendshipRepository, times(3)).findRecommendations(sender.getId(), pageable);
        verify(friendshipRepository, never()).searchByUsername(anyString(), anyString(), any(), any());
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void discoveryTreatsSqlWildcardsAsLiteralSearchCharacters() {
        var pageable = PageRequest.of(0, 10);
        when(friendshipRepository.searchByUsername(anyString(), anyString(), eq(sender.getId()), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        service.discover("  LEO%_\\  ", pageable, sender);

        var term = ArgumentCaptor.forClass(String.class);
        var pattern = ArgumentCaptor.forClass(String.class);
        verify(friendshipRepository).searchByUsername(term.capture(), pattern.capture(), eq(sender.getId()), eq(pageable));
        assertThat(term.getValue()).isEqualTo("leo%_\\");
        assertThat(pattern.getValue()).isEqualTo("%leo\\%\\_\\\\%");
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void usernameSearchAcceptsTheHandleWithOrWithoutAtSign() {
        var pageable = PageRequest.of(0, 20);
        when(friendshipRepository.searchByUsername("leonardo", "%leonardo%", sender.getId(), pageable))
                .thenReturn(Page.empty(pageable));

        service.discover("@Leonardo", pageable, sender);
        service.discover("  leonardo ", pageable, sender);
        service.discover(" @ LEONARDO", pageable, sender);

        verify(friendshipRepository, times(3)).searchByUsername("leonardo", "%leonardo%", sender.getId(), pageable);
        verify(friendshipRepository, never()).findRecommendations(any(), any());
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void recommendationsUseOnlyTheAuthenticatedUserAndKeepPagination() {
        var pageable = PageRequest.of(1, 2);
        var people = List.of(candidate(receiver, RelationshipStatus.NONE, 4));
        when(friendshipRepository.findRecommendations(sender.getId(), pageable))
                .thenReturn(new PageImpl<>(people, pageable, 3));

        var response = service.getRecommendations(pageable, sender);

        assertThat(response.getContent()).singleElement().satisfies(person -> {
            assertThat(person.id()).isEqualTo(receiver.getId());
            assertThat(person.relationshipStatus()).isEqualTo(RelationshipStatus.NONE);
            assertThat(person.mutualFriendsCount()).isEqualTo(4L);
        });
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getTotalElements()).isEqualTo(3);
        verify(friendshipRepository).findRecommendations(sender.getId(), pageable);
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void relationshipResolvesPendingRequestInEitherDirection() {
        when(userRepository.findById(receiver.getId())).thenReturn(Optional.of(receiver));
        FriendRequest outgoing = persisted(FriendRequest.between(sender, receiver));
        FriendRequest incoming = persisted(FriendRequest.between(receiver, sender));
        when(requestRepository.findPendingRequestBetweenUsers(sender.getId(), receiver.getId()))
                .thenReturn(Optional.of(outgoing))
                .thenReturn(Optional.of(incoming))
                .thenReturn(Optional.empty());
        assertThat(service.getRelationship(receiver.getId(), sender)).satisfies(response -> {
            assertThat(response.status()).isEqualTo(RelationshipStatus.REQUEST_SENT);
            assertThat(response.requestId()).isEqualTo(outgoing.getId());
        });
        assertThat(service.getRelationship(receiver.getId(), sender)).satisfies(response -> {
            assertThat(response.status()).isEqualTo(RelationshipStatus.REQUEST_RECEIVED);
            assertThat(response.requestId()).isEqualTo(incoming.getId());
        });
        assertThat(service.getRelationship(receiver.getId(), sender)).satisfies(response -> {
            assertThat(response.status()).isEqualTo(RelationshipStatus.NONE);
            assertThat(response.requestId()).isNull();
        });
    }

    @Test
    void relationshipReturnsFriendWithoutPendingRequestLookup() {
        when(userRepository.findById(receiver.getId())).thenReturn(Optional.of(receiver));
        when(friendshipRepository.existsFriendshipBetweenUsers(sender.getId(), receiver.getId())).thenReturn(true);
        assertThat(service.getRelationship(receiver.getId(), sender)).satisfies(response -> {
            assertThat(response.status()).isEqualTo(RelationshipStatus.FRIEND);
            assertThat(response.requestId()).isNull();
        });
        verifyNoInteractions(requestRepository);
    }

    @Test
    void relationshipRejectsSelfMissingAndArtificialUsers() {
        assertThatThrownBy(() -> service.getRelationship(sender.getId(), sender))
                .isInstanceOf(BadRequestException.class);
        when(userRepository.findById(receiver.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRelationship(receiver.getId(), sender))
                .isInstanceOf(NotFoundException.class);
        User fixture = user("fixture");
        ReflectionTestUtils.setField(fixture, "keycloakId", "seed:fixture");
        when(userRepository.findById(fixture.getId())).thenReturn(Optional.of(fixture));
        assertThatThrownBy(() -> service.getRelationship(fixture.getId(), sender))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(requestRepository, friendshipRepository);
    }

    @Test
    void requestsToArtificialUsersAreRejectedEvenByDirectId() {
        lockUsers();
        ReflectionTestUtils.setField(receiver, "keycloakId", "friend-seed:fixture");
        when(userRepository.getReferenceById(receiver.getId())).thenReturn(receiver);
        assertThatThrownBy(() -> service.sendRequest(receiver.getId(), sender)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(requestRepository, friendshipRepository);
    }

    @Test
    void discoveryRejectsSearchBeyondTheExistingFiftyCharacterLimit() {
        assertThatThrownBy(() -> service.discover("a".repeat(51), PageRequest.of(0, 10), sender))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(userRepository, requestRepository, friendshipRepository);
    }

    @Test
    void friendsListSelectsTheOtherParticipantFromEitherEndAndPreservesPagination() {
        var pageable = PageRequest.of(1, 2);
        Friendship currentUserFirst = persisted(Friendship.between(sender, receiver));
        Friendship currentUserSecond = persisted(Friendship.between(outsider, sender));
        when(friendshipRepository.findFriendsByUser(sender.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(currentUserFirst, currentUserSecond), pageable, 6));

        var response = service.getFriends(pageable, sender);

        assertThat(response.getContent()).extracting(friend -> friend.id())
                .containsExactly(receiver.getId(), outsider.getId());
        assertThat(response.getContent()).extracting(friend -> friend.friendsSince())
                .containsExactly(currentUserFirst.getCreatedAt(), currentUserSecond.getCreatedAt());
        assertThat(response.getNumber()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(2);
        assertThat(response.getTotalElements()).isEqualTo(6);
        verifyNoInteractions(userRepository, requestRepository);
    }

    @Test
    void requestListsUseTheAuthenticatedUserAndTheCorrectDirection() {
        var pageable = PageRequest.of(0, 10);
        FriendRequest received = persisted(FriendRequest.between(outsider, sender));
        FriendRequest sent = persisted(FriendRequest.between(sender, receiver));
        when(requestRepository.findReceivedPendingRequests(sender.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(received), pageable, 1));
        when(requestRepository.findSentPendingRequests(sender.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(sent), pageable, 1));

        assertThat(service.getReceivedRequests(pageable, sender).getContent())
                .singleElement().satisfies(request -> {
                    assertThat(request.id()).isEqualTo(received.getId());
                    assertThat(request.sender().id()).isEqualTo(outsider.getId());
                    assertThat(request.receiver().id()).isEqualTo(sender.getId());
                    assertThat(request.status()).isEqualTo(FriendRequestStatus.PENDING);
                    assertThat(request.respondedAt()).isNull();
                });
        assertThat(service.getSentRequests(pageable, sender).getContent())
                .singleElement().satisfies(request -> {
                    assertThat(request.id()).isEqualTo(sent.getId());
                    assertThat(request.sender().id()).isEqualTo(sender.getId());
                    assertThat(request.receiver().id()).isEqualTo(receiver.getId());
                    assertThat(request.status()).isEqualTo(FriendRequestStatus.PENDING);
                    assertThat(request.respondedAt()).isNull();
                });
        verifyNoInteractions(userRepository, friendshipRepository);
    }

    private static FriendDiscoveryCandidate candidate(User user, RelationshipStatus status, long mutuals) {
        FriendDiscoveryCandidate candidate = mock(FriendDiscoveryCandidate.class);
        when(candidate.getId()).thenReturn(user.getId());
        when(candidate.getUsername()).thenReturn(user.getUsername());
        when(candidate.getFirstName()).thenReturn(user.getFirstName());
        when(candidate.getLastName()).thenReturn(user.getLastName());
        when(candidate.getAvatarUrl()).thenReturn(user.getAvatarUrl());
        when(candidate.getRelationshipStatus()).thenReturn(status.name());
        when(candidate.getMutualFriendsCount()).thenReturn(mutuals);
        return candidate;
    }

    private FriendRequest pendingRequest() {
        FriendRequest request = persisted(FriendRequest.between(sender, receiver));
        when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        return request;
    }

    private void lockUsers() {
        when(userRepository.findByIdForUpdate(sender.getId())).thenReturn(Optional.of(sender));
        when(userRepository.findByIdForUpdate(receiver.getId())).thenReturn(Optional.of(receiver));
    }
}
