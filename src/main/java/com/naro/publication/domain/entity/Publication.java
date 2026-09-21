package com.naro.publication.domain.entity;

import com.naro.channel.domain.entity.Channel;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "publications")
@NoArgsConstructor
public class Publication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "channel_id",
            nullable = false,
            updatable = false
    )
    private Channel author;


    @Column(
            nullable = false,
            length = 2000
    )
    private String content;


    @Column(
            name = "image_key",
            nullable = false,
            length = 500
    )
    private String imageKey;


    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;


    public Publication(
            Channel author,
            String content,
            String imageKey
    ) {
        this.author = author;
        this.content = content;
        this.imageKey = imageKey;
    }


    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }
}
