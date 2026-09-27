package com.naro.friend.application.service;

import com.naro.exception.BadRequestException;
import com.naro.exception.ConflictException;
import com.naro.exception.ForbiddenException;
import com.naro.exception.NotFoundException;
import com.naro.friend.application.dto.FriendDiscoveryResponse;
import com.naro.friend.application.dto.FriendRequestResponse;
import com.naro.friend.application.dto.FriendResponse;
import com.naro.friend.application.dto.FriendRelationshipResponse;
import com.naro.friend.domain.entity.FriendRequest;
import com.naro.friend.domain.entity.Friendship;
import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.friend.domain.enums.RelationshipStatus;
import com.naro.friend.domain.repository.FriendDiscoveryCandidate;
import com.naro.friend.domain.repository.FriendRequestRepository;
import com.naro.friend.domain.repository.FriendshipRepository;
import com.naro.user.application.dto.UserSummaryResponse;
import com.naro.user.domain.entity.User;
import com.naro.user.domain.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class FriendServiceImpl implements FriendService {

    private final FriendRequestRepository requestRepository;
    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;

    public FriendServiceImpl(FriendRequestRepository requestRepository,
                             FriendshipRepository friendshipRepository,
                             UserRepository userRepository) {
        this.requestRepository = requestRepository;
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public FriendRelationshipResponse getRelationship(UUID targetUserId, User currentUser) {
        if (currentUser.getId().equals(targetUserId)) {
            throw new BadRequestException("A user cannot query a friendship with themselves");
        }
        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        requireRealUser(target);
        if (friendshipRepository.existsFriendshipBetweenUsers(currentUser.getId(), targetUserId)) {
            return new FriendRelationshipResponse(RelationshipStatus.FRIEND, null);
        }
        return requestRepository.findPendingRequestBetweenUsers(currentUser.getId(), targetUserId)
                .map(request -> new FriendRelationshipResponse(
                        request.getSender().getId().equals(currentUser.getId())
                                ? RelationshipStatus.REQUEST_SENT : RelationshipStatus.REQUEST_RECEIVED,
                        request.getId()))
                .orElseGet(() -> new FriendRelationshipResponse(RelationshipStatus.NONE, null));
    }

    @Override
    @Transactional
    public FriendRequestResponse sendRequest(UUID targetUserId, User currentUser) {
        UUID currentUserId = currentUser.getId();
        if (currentUserId.equals(targetUserId)) {
            throw new BadRequestException("A user cannot send a friend request to themselves");
        }

        lockUsers(currentUserId, targetUserId);
        User receiver = userRepository.getReferenceById(targetUserId);
        requireRealUser(receiver);
        if (friendshipRepository.existsFriendshipBetweenUsers(currentUserId, targetUserId)) {
            throw new ConflictException("Users are already friends");
        }
        if (requestRepository.findPendingRequestBetweenUsers(currentUserId, targetUserId).isPresent()) {
            throw new ConflictException("A pending friend request already exists between these users");
        }

        try {
            return toResponse(requestRepository.saveAndFlush(FriendRequest.between(currentUser, receiver)));
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictException("A pending friend request already exists between these users");
        }
    }

    @Override
    @Transactional
    public FriendRequestResponse acceptRequest(UUID requestId, User currentUser) {
        FriendRequest request = findRequestForUpdate(requestId);
        requireReceiver(request, currentUser);
        requirePending(request);

        UUID senderId = request.getSender().getId();
        UUID receiverId = request.getReceiver().getId();
        lockUsers(senderId, receiverId);
        if (friendshipRepository.existsFriendshipBetweenUsers(senderId, receiverId)) {
            throw new ConflictException("Users are already friends");
        }

        try {
            request.respond(FriendRequestStatus.ACCEPTED);
            requestRepository.saveAndFlush(request);
            friendshipRepository.saveAndFlush(Friendship.between(request.getSender(), request.getReceiver()));
            return toResponse(request);
        } catch (DataIntegrityViolationException exception) {
            // A exceção faz rollback da resposta e da amizade na mesma transação.
            throw new ConflictException("Users are already friends");
        }
    }

    @Override
    @Transactional
    public FriendRequestResponse rejectRequest(UUID requestId, User currentUser) {
        FriendRequest request = findRequestForUpdate(requestId);
        requireReceiver(request, currentUser);
        requirePending(request);
        request.respond(FriendRequestStatus.REJECTED);
        return toResponse(requestRepository.saveAndFlush(request));
    }

    @Override
    @Transactional
    public void cancelRequest(UUID requestId, User currentUser) {
        FriendRequest request = findRequestForUpdate(requestId);
        if (!request.getSender().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Only the sender can cancel this friend request");
        }
        requirePending(request);
        request.respond(FriendRequestStatus.CANCELED);
        requestRepository.saveAndFlush(request);
    }

    @Override
    @Transactional
    public void removeFriend(UUID targetUserId, User currentUser) {
        if (currentUser.getId().equals(targetUserId)) {
            throw new BadRequestException("A user cannot remove themselves as a friend");
        }
        lockUsers(currentUser.getId(), targetUserId);
        Friendship friendship = friendshipRepository
                .findFriendshipBetweenUsers(currentUser.getId(), targetUserId)
                .orElseThrow(() -> new NotFoundException("Friendship not found"));
        friendshipRepository.delete(friendship);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FriendDiscoveryResponse> discover(String search, Pageable pageable, User currentUser) {
        String normalized = search == null ? "" : search.trim();
        // Same limit as users.email: any stored address can be searched.
        if (normalized.length() > 255) {
            throw new BadRequestException("Search query must have at most 255 characters");
        }
        // "@leonardo" and "leonardo" are the same search: the handle is the username.
        String term = (normalized.startsWith("@") ? normalized.substring(1).trim() : normalized)
                .toLowerCase(Locale.ROOT);
        // Nobody is suggested: without a term there is nothing to search, and the
        // platform is never listed.
        if (term.isEmpty()) return Page.empty(pageable);
        return friendshipRepository.searchByUsernameOrEmail(term, containsPattern(term), currentUser.getId(), pageable)
                .map(FriendServiceImpl::toDiscoveryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FriendResponse> getFriends(Pageable pageable, User currentUser) {
        return friendshipRepository.findFriendsByUser(currentUser.getId(), pageable).map(friendship -> {
            User friend = otherUser(friendship, currentUser);
            return new FriendResponse(friend.getId(), friend.getUsername(), friend.getFirstName(),
                    friend.getLastName(), friend.getAvatarUrl(), friendship.getCreatedAt());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FriendResponse> searchFriends(String search, Pageable pageable, User currentUser) {
        String normalized = search == null ? "" : search.trim();
        if (normalized.length() > 50) {
            throw new BadRequestException("Search query must have at most 50 characters");
        }
        if (normalized.isEmpty()) return getFriends(pageable, currentUser);
        String pattern = containsPattern(normalized.toLowerCase(Locale.ROOT));
        return friendshipRepository.searchFriendsByUser(currentUser.getId(), pattern, pageable).map(friendship -> {
            User friend = otherUser(friendship, currentUser);
            return new FriendResponse(friend.getId(), friend.getUsername(), friend.getFirstName(),
                    friend.getLastName(), friend.getAvatarUrl(), friendship.getCreatedAt());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FriendRequestResponse> getReceivedRequests(Pageable pageable, User currentUser) {
        return requestRepository.findReceivedPendingRequests(currentUser.getId(), pageable)
                .map(FriendServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FriendRequestResponse> getSentRequests(Pageable pageable, User currentUser) {
        return requestRepository.findSentPendingRequests(currentUser.getId(), pageable)
                .map(FriendServiceImpl::toResponse);
    }

    /** LIKE wildcards typed by the user are literal text: "%" or "_" never match everything. */
    private static String containsPattern(String lowerCaseTerm) {
        return "%" + lowerCaseTerm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static void requireRealUser(User user) {
        if (user.getKeycloakId().startsWith("seed:") || user.getKeycloakId().startsWith("friend-seed:")) {
            throw new NotFoundException("User not found");
        }
    }

    private FriendRequest findRequestForUpdate(UUID requestId) {
        return requestRepository.findByIdForUpdate(requestId)
                .orElseThrow(() -> new NotFoundException("Friend request not found"));
    }

    /*
     * Ordem fixa evita deadlocks entre A->B e B->A. Os locks também impedem
     * que um envio concorrente à aceitação deixe uma solicitação entre amigos.
     */
    private void lockUsers(UUID firstId, UUID secondId) {
        UUID lower = firstId.toString().compareTo(secondId.toString()) < 0 ? firstId : secondId;
        UUID upper = lower.equals(firstId) ? secondId : firstId;
        userRepository.findByIdForUpdate(lower)
                .orElseThrow(() -> new NotFoundException("User not found"));
        userRepository.findByIdForUpdate(upper)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private static void requireReceiver(FriendRequest request, User currentUser) {
        if (!request.getReceiver().getId().equals(currentUser.getId())) {
            throw new ForbiddenException("Only the receiver can respond to this friend request");
        }
    }

    private static void requirePending(FriendRequest request) {
        if (request.getStatus() != FriendRequestStatus.PENDING) {
            throw new ConflictException("Friend request has already been answered");
        }
    }

    private static User otherUser(Friendship friendship, User currentUser) {
        return friendship.getUserA().getId().equals(currentUser.getId())
                ? friendship.getUserB() : friendship.getUserA();
    }

    private static FriendRequestResponse toResponse(FriendRequest request) {
        return new FriendRequestResponse(request.getId(), toUserSummary(request.getSender()),
                toUserSummary(request.getReceiver()), request.getStatus(), request.getCreatedAt(),
                request.getRespondedAt());
    }

    private static UserSummaryResponse toUserSummary(User user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getFirstName(),
                user.getLastName(), user.getAvatarUrl());
    }

    private static FriendDiscoveryResponse toDiscoveryResponse(FriendDiscoveryCandidate person) {
        return new FriendDiscoveryResponse(person.getId(), person.getUsername(), person.getFirstName(),
                person.getLastName(), person.getAvatarUrl(),
                RelationshipStatus.valueOf(person.getRelationshipStatus()), person.getMutualFriendsCount());
    }
}
