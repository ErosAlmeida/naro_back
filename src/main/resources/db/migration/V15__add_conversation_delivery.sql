-- Confirmação de entrega ao lado da leitura (tiques do chat):
--
--   ✓        salva no servidor
--   ✓✓       chegou a um aparelho do destinatário (last_delivered_at)
--   ✓✓ azul  o destinatário abriu a conversa (last_read_at)
--
-- Uma mensagem de outra pessoa está entregue/lida quando foi criada até o
-- respectivo marcador. Entregue pode existir sem leitura (aparelho recebeu,
-- conversa ainda não aberta), por isso last_read_at passa a aceitar NULL;
-- as consultas de não lidas já tratam NULL como "nada lido".
--
-- Nenhuma linha some e nenhuma leitura muda: o que já foi lido conta como
-- entregue até o mesmo instante.
ALTER TABLE conversation_reads
    ALTER COLUMN last_read_at DROP NOT NULL;

ALTER TABLE conversation_reads
    ADD COLUMN last_delivered_at TIMESTAMPTZ;

UPDATE conversation_reads
SET last_delivered_at = last_read_at;
