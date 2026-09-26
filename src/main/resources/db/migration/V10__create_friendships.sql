CREATE TABLE friend_requests
(
    id            UUID        NOT NULL,
    sender_id     UUID        NOT NULL,
    receiver_id   UUID        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ NOT NULL,
    responded_at  TIMESTAMPTZ,

    CONSTRAINT pk_friend_requests PRIMARY KEY (id),
    CONSTRAINT fk_friend_requests_sender FOREIGN KEY (sender_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_friend_requests_receiver FOREIGN KEY (receiver_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_friend_requests_not_self CHECK (sender_id <> receiver_id),
    CONSTRAINT ck_friend_requests_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELED')),
    CONSTRAINT ck_friend_requests_responded_at CHECK (
        (status = 'PENDING' AND responded_at IS NULL)
        OR (status <> 'PENDING' AND responded_at IS NOT NULL)
    )
);

-- Only pending requests are exclusive; answered requests remain as history.
CREATE UNIQUE INDEX uk_friend_requests_pending_pair
    ON friend_requests (LEAST(sender_id, receiver_id), GREATEST(sender_id, receiver_id))
    WHERE status = 'PENDING';

CREATE INDEX idx_friend_requests_receiver_pending_created
    ON friend_requests (receiver_id, created_at DESC, id DESC)
    WHERE status = 'PENDING';

CREATE INDEX idx_friend_requests_sender_pending_created
    ON friend_requests (sender_id, created_at DESC, id DESC)
    WHERE status = 'PENDING';

CREATE TABLE friendships
(
    id          UUID        NOT NULL,
    user_a_id   UUID        NOT NULL,
    user_b_id   UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_friendships PRIMARY KEY (id),
    CONSTRAINT fk_friendships_user_a FOREIGN KEY (user_a_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_friendships_user_b FOREIGN KEY (user_b_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_friendships_not_self CHECK (user_a_id <> user_b_id)
);

-- Both orders identify the same single friendship.
CREATE UNIQUE INDEX uk_friendships_pair
    ON friendships (LEAST(user_a_id, user_b_id), GREATEST(user_a_id, user_b_id));

CREATE INDEX idx_friendships_user_a_created
    ON friendships (user_a_id, created_at DESC, id DESC);

CREATE INDEX idx_friendships_user_b_created
    ON friendships (user_b_id, created_at DESC, id DESC);
