CREATE TABLE channels
(
    id                UUID         NOT NULL,
    owner_id          UUID         NOT NULL,
    name              VARCHAR(100) NOT NULL,
    slug              VARCHAR(100) NOT NULL,
    description       VARCHAR(500),
    profile_image_key VARCHAR(500),
    banner_image_key  VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_channels PRIMARY KEY (id),
    CONSTRAINT uk_channels_slug UNIQUE (slug),
    CONSTRAINT fk_channels_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_channels_owner_id ON channels (owner_id);
