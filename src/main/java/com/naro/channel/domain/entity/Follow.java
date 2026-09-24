package com.naro.channel.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(
        name = "channel_follows",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_channel_follows_pair",
                columnNames = {"follower_channel_id", "following_channel_id"}
        ),
        indexes = {
                @Index(name = "idx_channel_follows_follower_created", columnList = "follower_channel_id, created_at DESC, id DESC"),
                @Index(name = "idx_channel_follows_following_created", columnList = "following_channel_id, created_at DESC, id DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "follower_channel_id", nullable = false, updatable = false)
    private Channel followerChannel;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "following_channel_id", nullable = false, updatable = false)
    private Channel followingChannel;

    @Column(name = "favorite", nullable = false)
    private boolean favorite;

    @Column(name = "interaction_count", nullable = false)
    private long interactionCount;

    @Column(name = "last_interaction_at")
    private Instant lastInteractionAt;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Follow between(Channel followerChannel, Channel followingChannel) {
        Follow follow = new Follow();
        follow.followerChannel = Objects.requireNonNull(followerChannel, "followerChannel");
        follow.followingChannel = Objects.requireNonNull(followingChannel, "followingChannel");
        return follow;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    @PrePersist
    private void prePersist() {
        createdAt = Instant.now();
    }
}
