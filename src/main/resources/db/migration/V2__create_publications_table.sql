CREATE TABLE publications
(
    id         UUID PRIMARY KEY,
    author_id  UUID          NOT NULL,
    content    VARCHAR(2000) NOT NULL,
    image_key  VARCHAR(500)  NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_publications_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_publications_author_id ON publications (author_id);
CREATE INDEX idx_publications_created_at ON publications (created_at DESC);