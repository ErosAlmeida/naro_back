-- Um canal por usuário com atividade anterior. UUID e slug determinísticos
-- permitem migrar todos os registros sem perder a autoria ou duplicar likes.
-- V7 cria a tabela vazia; o UUID do usuário é reutilizado apenas no canal legado.
INSERT INTO channels (id, owner_id, name, slug, description, created_at, updated_at)
SELECT u.id, u.id, u.username, 'legacy-' || u.id::text, u.bio, u.created_at, u.updated_at
FROM users u
WHERE EXISTS (SELECT 1 FROM publications p WHERE p.author_id = u.id)
   OR EXISTS (SELECT 1 FROM comments c WHERE c.author_id = u.id)
   OR EXISTS (SELECT 1 FROM likes l WHERE l.user_id = u.id);

ALTER TABLE publications DROP CONSTRAINT fk_publications_author;
ALTER TABLE publications RENAME COLUMN author_id TO channel_id;
ALTER TABLE publications ADD CONSTRAINT fk_publications_channel
    FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE CASCADE;
DROP INDEX idx_publications_author_id;
CREATE INDEX idx_publications_channel_created ON publications (channel_id, created_at DESC, id DESC);
DROP INDEX idx_publications_created_at;
CREATE INDEX idx_publications_created_at ON publications (created_at DESC, id DESC);

ALTER TABLE comments DROP CONSTRAINT fk_comments_author;
ALTER TABLE comments RENAME COLUMN author_id TO channel_id;
ALTER TABLE comments ADD CONSTRAINT fk_comments_channel
    FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE CASCADE;
ALTER INDEX idx_comments_author RENAME TO idx_comments_channel;

ALTER TABLE likes DROP CONSTRAINT fk_likes_user;
ALTER TABLE likes RENAME COLUMN user_id TO channel_id;
ALTER TABLE likes ADD CONSTRAINT fk_likes_channel
    FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE CASCADE;

-- Os índices existentes passam a apontar para channel_id após o rename.
-- Mantêm um like por canal e alvo, inclusive em requisições concorrentes.
ALTER INDEX uk_likes_publication_user RENAME TO uk_likes_publication_channel;
ALTER INDEX uk_likes_comment_user RENAME TO uk_likes_comment_channel;
ALTER INDEX idx_likes_user RENAME TO idx_likes_channel;
