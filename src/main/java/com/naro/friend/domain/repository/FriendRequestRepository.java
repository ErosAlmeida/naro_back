package com.naro.friend.domain.repository;

import com.naro.friend.domain.entity.FriendRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"sender", "receiver"})
    @Query("SELECT r FROM FriendRequest r WHERE r.id = :requestId")
    Optional<FriendRequest> findByIdForUpdate(@Param("requestId") UUID requestId);

    @EntityGraph(attributePaths = {"sender", "receiver"})
    @Query("""
            SELECT r FROM FriendRequest r
            WHERE r.status = com.naro.friend.domain.enums.FriendRequestStatus.PENDING
              AND ((r.sender.id = :first AND r.receiver.id = :second)
                OR (r.sender.id = :second AND r.receiver.id = :first))
            """)
    Optional<FriendRequest> findPendingRequestBetweenUsers(@Param("first") UUID first, @Param("second") UUID second);

    @EntityGraph(attributePaths = {"sender", "receiver"})
    @Query("""
            SELECT r FROM FriendRequest r
            WHERE r.receiver.id = :userId
              AND r.status = com.naro.friend.domain.enums.FriendRequestStatus.PENDING
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    Page<FriendRequest> findReceivedPendingRequests(@Param("userId") UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "receiver"})
    @Query("""
            SELECT r FROM FriendRequest r
            WHERE r.sender.id = :userId
              AND r.status = com.naro.friend.domain.enums.FriendRequestStatus.PENDING
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    Page<FriendRequest> findSentPendingRequests(@Param("userId") UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "receiver"})
    @Query("""
            SELECT r FROM FriendRequest r
            WHERE r.status = com.naro.friend.domain.enums.FriendRequestStatus.PENDING
              AND ((r.sender.id = :userId AND r.receiver.id IN :targetIds)
                OR (r.receiver.id = :userId AND r.sender.id IN :targetIds))
            """)
    List<FriendRequest> findPendingRequestsAmong(@Param("userId") UUID userId, @Param("targetIds") Collection<UUID> targetIds);
}
