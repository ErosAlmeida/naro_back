package com.naro.chat.domain.entity;

import com.naro.chat.domain.enums.ConversationType;
import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Entity
@Table(name = "conversations")
@NoArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @Enumerated(EnumType.STRING)
    @Column(
            nullable = false,
            length = 30
    )
    private ConversationType type;


    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "conversation_participants",

            joinColumns = @JoinColumn(
                    name = "conversation_id"
            ),

            inverseJoinColumns = @JoinColumn(
                    name = "user_id"
            )
    )
    private Set<User> participants =
            new HashSet<>();


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


    public Conversation(
            ConversationType type,
            Collection<User> participants
    ) {
        this.type = type;
        this.participants.addAll(participants);
    }


    @PrePersist
    private void prePersist() {

        Instant now = Instant.now();

        this.createdAt = now;
        this.updatedAt = now;
    }


    public void touch() {
        this.updatedAt = Instant.now();
    }
}