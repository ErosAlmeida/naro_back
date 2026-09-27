package com.naro.friend.application.service;

import com.naro.friend.application.dto.FriendDiscoveryResponse;
import com.naro.friend.application.dto.FriendRequestResponse;
import com.naro.friend.application.dto.FriendResponse;
import com.naro.friend.application.dto.FriendRelationshipResponse;
import com.naro.user.domain.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface FriendService {

    FriendRelationshipResponse getRelationship(UUID targetUserId, User currentUser);

    FriendRequestResponse sendRequest(UUID targetUserId, User currentUser);

    FriendRequestResponse acceptRequest(UUID requestId, User currentUser);

    FriendRequestResponse rejectRequest(UUID requestId, User currentUser);

    void cancelRequest(UUID requestId, User currentUser);

    void removeFriend(UUID targetUserId, User currentUser);

    Page<FriendDiscoveryResponse> discover(String search, Pageable pageable, User currentUser);

    Page<FriendResponse> getFriends(Pageable pageable, User currentUser);

    Page<FriendResponse> searchFriends(String search, Pageable pageable, User currentUser);

    Page<FriendRequestResponse> getReceivedRequests(Pageable pageable, User currentUser);

    Page<FriendRequestResponse> getSentRequests(Pageable pageable, User currentUser);
}
