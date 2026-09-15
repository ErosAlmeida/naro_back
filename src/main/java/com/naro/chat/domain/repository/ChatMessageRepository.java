package com.naro.chat.domain.repository;

import com.naro.chat.domain.entity.ChatMessage;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatMessageRepository
        extends JpaRepository<ChatMessage, UUID> {


    @EntityGraph(attributePaths = "sender")
    Page<ChatMessage>
    findByConversation_IdOrderByCreatedAtDesc(
            UUID conversationId,
            Pageable pageable
    );
}