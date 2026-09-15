CREATE TABLE conversations
(
    id         UUID PRIMARY KEY,
    type       VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);


CREATE TABLE conversation_participants
(
    conversation_id UUID NOT NULL,
    user_id         UUID NOT NULL,

    PRIMARY KEY (conversation_id, user_id),

    CONSTRAINT fk_conversation_participant_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES conversations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_conversation_participant_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
);


CREATE TABLE chat_messages
(
    id              UUID PRIMARY KEY,
    conversation_id UUID          NOT NULL,
    sender_id       UUID          NOT NULL,
    content         VARCHAR(4000) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT fk_chat_message_conversation
        FOREIGN KEY (conversation_id)
            REFERENCES conversations (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_chat_message_sender
        FOREIGN KEY (sender_id)
            REFERENCES users (id)
);


CREATE INDEX idx_conversation_participants_user
    ON conversation_participants (user_id);


CREATE INDEX idx_chat_messages_conversation_created
    ON chat_messages (conversation_id, created_at DESC);


CREATE INDEX idx_chat_messages_sender
    ON chat_messages (sender_id);