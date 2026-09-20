CREATE TABLE likes
(
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    publication_id UUID,
    comment_id     UUID,
    created_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_likes
        PRIMARY KEY (id),

    CONSTRAINT fk_likes_user
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_likes_publication
        FOREIGN KEY (publication_id)
            REFERENCES publications (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_likes_comment
        FOREIGN KEY (comment_id)
            REFERENCES comments (id)
            ON DELETE CASCADE,

    -- Cada like pertence a uma publicação ou a um comentário, nunca aos dois.
    CONSTRAINT ck_likes_publication_xor_comment
        CHECK (
            (publication_id IS NOT NULL AND comment_id IS NULL)
            OR
            (publication_id IS NULL AND comment_id IS NOT NULL)
        )
);


-- Um like por usuário em cada publicação ou comentário.
CREATE UNIQUE INDEX uk_likes_publication_user
    ON likes (publication_id, user_id)
    WHERE publication_id IS NOT NULL;

CREATE UNIQUE INDEX uk_likes_comment_user
    ON likes (comment_id, user_id)
    WHERE comment_id IS NOT NULL;

CREATE INDEX idx_likes_user
    ON likes (user_id);
