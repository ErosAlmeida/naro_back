package com.naro.channel.domain.entity;

import com.naro.user.domain.entity.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "channels",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_channels_slug",
                        columnNames = "slug"
                )
        },
        indexes = {
                @Index(
                        name = "idx_channels_owner_id",
                        columnList = "owner_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Channel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "owner_id",
            nullable = false,
            updatable = false
    )
    private User owner;

    @Column(
            name = "name",
            nullable = false,
            length = 100
    )
    private String name;

    @Column(
            name = "slug",
            nullable = false,
            length = 100
    )
    private String slug;

    @Column(
            name = "description",
            length = 500
    )
    private String description;

    @Column(
            name = "profile_image_key",
            length = 500
    )
    private String profileImageKey;

    @Column(
            name = "banner_image_key",
            length = 500
    )
    private String bannerImageKey;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    @PrePersist
    private void prePersist() {
        Instant now = Instant.now();

        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
