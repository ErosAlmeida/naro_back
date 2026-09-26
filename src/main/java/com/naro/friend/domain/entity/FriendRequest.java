package com.naro.friend.domain.entity;

import com.naro.friend.domain.enums.FriendRequestStatus;
import com.naro.user.domain.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "friend_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FriendRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false, updatable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false, updatable = false)
    private User receiver;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private FriendRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public static FriendRequest between(User sender, User receiver) {
        FriendRequest request = new FriendRequest();
        request.sender = Objects.requireNonNull(sender, "sender");
        request.receiver = Objects.requireNonNull(receiver, "receiver");
        request.status = FriendRequestStatus.PENDING;
        return request;
    }

    public void respond(FriendRequestStatus status) {
        this.status = Objects.requireNonNull(status, "status");
        this.respondedAt = Instant.now();
    }

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
