/*
 * Momento em que o usuário deixou de ter qualquer conexão
 * WebSocket ativa ("visto por último").
 *
 * Gravado apenas na transição para OFFLINE:
 * nunca por heartbeat, tecla ou mensagem.
 */
ALTER TABLE users
    ADD COLUMN last_seen_at TIMESTAMP WITH TIME ZONE;
