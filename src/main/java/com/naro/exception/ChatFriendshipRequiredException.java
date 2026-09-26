package com.naro.exception;

import java.util.UUID;

/** Carries correlation only for the authenticated sender's STOMP error queue. */
public class ChatFriendshipRequiredException extends ForbiddenException {

    private final UUID conversationId;
    private final UUID clientMessageId;

    public ChatFriendshipRequiredException(UUID conversationId, UUID clientMessageId) {
        super("You can only message your friends");
        this.conversationId = conversationId;
        this.clientMessageId = clientMessageId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }
}
