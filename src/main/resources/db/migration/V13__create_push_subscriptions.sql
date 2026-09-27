-- Web Push: uma inscrição por navegador/dispositivo. Veja docs/notifications.md.
--
-- Só cria tabela nova; nada existente muda. O dono vem sempre do JWT de quem
-- cadastrou (nunca do corpo da requisição). endpoint é a URL do push service
-- (FCM, Mozilla, Apple, Windows) e funciona como segredo: não vai para logs.
-- p256dh e auth são as chaves públicas do navegador (RFC 8291); nenhum token
-- da conta é guardado aqui.
CREATE TABLE push_subscriptions
(
    id           UUID          NOT NULL,
    user_id      UUID          NOT NULL,
    endpoint     VARCHAR(2048) NOT NULL,
    p256dh       VARCHAR(128)  NOT NULL,
    auth         VARCHAR(64)   NOT NULL,
    device_name  VARCHAR(100),
    user_agent   VARCHAR(512),
    enabled      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL,
    last_used_at TIMESTAMPTZ,

    CONSTRAINT pk_push_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_push_subscriptions_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- Um navegador tem um único endpoint: cadastrar de novo atualiza a mesma linha.
    CONSTRAINT uk_push_subscriptions_endpoint UNIQUE (endpoint)
);

CREATE INDEX idx_push_subscriptions_user
    ON push_subscriptions (user_id);
