package com.naro.friend.domain.repository;

import java.util.UUID;

/** SQL projection keeps discovery to one paginated query, without loading entities. */
public interface FriendDiscoveryCandidate {
    UUID getId();
    String getUsername();
    String getFirstName();
    String getLastName();
    String getAvatarUrl();
    String getRelationshipStatus();
    long getMutualFriendsCount();
}
