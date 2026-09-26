package com.naro.friend.domain.entity;

import com.naro.user.domain.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "friendships")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_a_id", nullable = false, updatable = false)
    private User userA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_b_id", nullable = false, updatable = false)
    private User userB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Friendship between(User userA, User userB) {
        Friendship friendship = new Friendship();
        friendship.userA = Objects.requireNonNull(userA, "userA");
        friendship.userB = Objects.requireNonNull(userB, "userB");
        return friendship;
    }

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
