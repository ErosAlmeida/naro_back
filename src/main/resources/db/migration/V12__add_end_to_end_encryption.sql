-- Criptografia ponta a ponta (E2EE) do chat privado. Veja docs/e2ee.md.
--
-- Compatível com o banco em produção: nenhuma tabela ou coluna é removida,
-- nenhuma mensagem é reescrita. Mensagens antigas continuam com `content`
-- (encrypted = false); mensagens E2EE guardam somente o ciphertext.

ALTER TABLE chat_messages
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE chat_messages
    ADD COLUMN encrypted          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ciphertext         TEXT,
    ADD COLUMN encryption_iv      VARCHAR(32),
    ADD COLUMN encryption_version INTEGER;

-- Uma mensagem é exatamente uma das duas formas: nunca plaintext e ciphertext
-- juntos, nunca uma mensagem E2EE sem IV ou versão.
ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_payload CHECK (
        (encrypted = FALSE
            AND content IS NOT NULL
            AND ciphertext IS NULL
            AND encryption_iv IS NULL
            AND encryption_version IS NULL)
        OR
        (encrypted = TRUE
            AND content IS NULL
            AND ciphertext IS NOT NULL
            AND encryption_iv IS NOT NULL
            AND encryption_version IS NOT NULL)
    );


-- Par de chaves de identidade (ECDH) do usuário, um por usuário e imutável.
-- A chave privada chega cifrada com a Master Key; o servidor nunca a lê.
-- master_key_proof_hash é SHA-256 de um HMAC derivado da Master Key: permite
-- exigir posse da Master Key para cadastrar ou revogar passkeys, sem guardá-la.
CREATE TABLE user_encryption_identities
(
    user_id               UUID          NOT NULL,
    public_key            VARCHAR(128)  NOT NULL,
    wrapped_private_key   VARCHAR(1024) NOT NULL,
    private_key_iv        VARCHAR(32)   NOT NULL,
    master_key_proof_hash VARCHAR(64)   NOT NULL,
    algorithm             VARCHAR(30)   NOT NULL,
    version               INTEGER       NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_user_encryption_identities PRIMARY KEY (user_id),
    CONSTRAINT fk_user_encryption_identities_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);


-- A mesma Master Key, cifrada uma vez por passkey (um registro por dispositivo
-- ou gerenciador de senhas). Só existe chave para quem já tem identidade.
CREATE TABLE user_encryption_keys
(
    id                 UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    credential_id      VARCHAR(1400) NOT NULL,
    wrapped_master_key VARCHAR(128)  NOT NULL,
    iv                 VARCHAR(32)   NOT NULL,
    algorithm          VARCHAR(30)   NOT NULL,
    version            INTEGER       NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_user_encryption_keys PRIMARY KEY (id),
    CONSTRAINT fk_user_encryption_keys_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_encryption_keys_identity FOREIGN KEY (user_id)
        REFERENCES user_encryption_identities (user_id) ON DELETE CASCADE,
    CONSTRAINT uk_user_encryption_keys_credential UNIQUE (user_id, credential_id)
);
