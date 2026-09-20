CREATE TABLE comments
(
    id                UUID          NOT NULL,
    publication_id    UUID          NOT NULL,
    parent_comment_id UUID,
    author_id         UUID          NOT NULL,
    content           VARCHAR(1000) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_comments
        PRIMARY KEY (id),

    CONSTRAINT uk_comments_id_publication
        UNIQUE (id, publication_id),

    CONSTRAINT fk_comments_publication
        FOREIGN KEY (publication_id)
            REFERENCES publications (id)
            ON DELETE CASCADE,

    CONSTRAINT fk_comments_author
        FOREIGN KEY (author_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    -- Respostas pertencem à mesma publicação do pai e são removidas com ele.
    CONSTRAINT fk_comments_parent
        FOREIGN KEY (parent_comment_id, publication_id)
            REFERENCES comments (id, publication_id)
            ON DELETE CASCADE,

    CONSTRAINT ck_comments_not_own_parent
        CHECK (parent_comment_id <> id),

    CONSTRAINT ck_comments_content_not_blank
        CHECK (btrim(content) <> '')
);


CREATE INDEX idx_comments_publication_created
    ON comments (publication_id, created_at DESC);

CREATE INDEX idx_comments_parent_created
    ON comments (parent_comment_id, created_at)
    WHERE parent_comment_id IS NOT NULL;

CREATE INDEX idx_comments_author
    ON comments (author_id);
