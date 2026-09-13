CREATE TABLE users
(
    id          UUID         NOT NULL,
    keycloak_id VARCHAR(255) NOT NULL,
    username    VARCHAR(100) NOT NULL,
    email       VARCHAR(255),
    first_name  VARCHAR(100),
    last_name   VARCHAR(100),
    bio         VARCHAR(500),
    avatar_url  VARCHAR(1000),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT pk_users
        PRIMARY KEY (id),

    CONSTRAINT uk_users_keycloak_id
        UNIQUE (keycloak_id),

    CONSTRAINT uk_users_username
        UNIQUE (username)
);