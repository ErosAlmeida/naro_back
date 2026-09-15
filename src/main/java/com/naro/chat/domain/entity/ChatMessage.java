package com.naro.chat.domain.entity;

import com.naro.user.domain.entity.User;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "chat_messages")
@NoArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "conversation_id",
            nullable = false
    )
    private Conversation conversation;


    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(
            name = "sender_id",
            nullable = false
    )
    private User sender;


    @Column(
            nullable = false,
            length = 4000
    )
    private String content;


    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;


    public ChatMessage(
            Conversation conversation,
            User sender,
            String content
    ) {
        this.conversation = conversation;
        this.sender = sender;
        this.content = content;
    }


    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }
}