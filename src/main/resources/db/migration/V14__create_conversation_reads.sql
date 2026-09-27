-- Até onde cada participante leu cada conversa: base do contador de não lidas
-- e do badge. Uma mensagem é não lida quando veio de outra pessoa e foi criada
-- depois de last_read_at (ou quando ainda não há linha para o participante).
--
-- Só cria tabela nova. A FK composta garante que apenas participantes têm
-- marcador de leitura, e o marcador some junto com a participação.
CREATE TABLE conversation_reads
(
    conversation_id UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    last_read_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_conversation_reads PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_conversation_reads_participant FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversation_participants (conversation_id, user_id) ON DELETE CASCADE
);

-- Conversas que já existiam começam lidas: sem isso, todo o histórico de
-- produção apareceria como "não lido" no primeiro acesso depois do deploy.
INSERT INTO conversation_reads (conversation_id, user_id, last_read_at)
SELECT conversation_id, user_id, now()
FROM conversation_participants;
