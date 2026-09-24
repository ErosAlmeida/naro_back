CREATE TABLE channel_follows
(
    id                    UUID        NOT NULL,
    follower_channel_id   UUID        NOT NULL,
    following_channel_id  UUID        NOT NULL,
    favorite              BOOLEAN     NOT NULL DEFAULT FALSE,
    interaction_count     BIGINT      NOT NULL DEFAULT 0,
    last_interaction_at   TIMESTAMPTZ,
    relevance_score       DOUBLE PRECISION,
    created_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_channel_follows PRIMARY KEY (id),
    CONSTRAINT fk_channel_follows_follower FOREIGN KEY (follower_channel_id)
        REFERENCES channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_channel_follows_following FOREIGN KEY (following_channel_id)
        REFERENCES channels (id) ON DELETE CASCADE,
    CONSTRAINT uk_channel_follows_pair UNIQUE (follower_channel_id, following_channel_id),
    CONSTRAINT ck_channel_follows_not_self CHECK (follower_channel_id <> following_channel_id),
    CONSTRAINT ck_channel_follows_interaction_count CHECK (interaction_count >= 0),
    CONSTRAINT ck_channel_follows_relevance_score CHECK (relevance_score IS NULL OR relevance_score BETWEEN 0 AND 1)
);

CREATE INDEX idx_channel_follows_follower_created
    ON channel_follows (follower_channel_id, created_at DESC, id DESC);

CREATE INDEX idx_channel_follows_following_created
    ON channel_follows (following_channel_id, created_at DESC, id DESC);
