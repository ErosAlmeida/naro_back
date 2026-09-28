package com.naro.chat.application.dto;

import java.time.Instant;

/**
 * Até onde as mensagens de quem pergunta chegaram e foram lidas pelos
 * outros participantes da conversa (num grupo, por todos eles). Uma
 * mensagem enviada em createdAt está:
 *
 *   lida       (✓✓ azul)  quando {@code createdAt <= readAt}
 *   entregue   (✓✓)       quando {@code createdAt <= deliveredAt}
 *   enviada    (✓)        nos outros casos
 *
 * Só metadados: nada do conteúdo, que no E2EE nem o servidor conhece.
 *
 * @param deliveredAt null enquanto nada chegou a um aparelho de todos
 * @param readAt      null enquanto a conversa não foi aberta por todos
 */
public record MessageReceipts(

        Instant deliveredAt,

        Instant readAt

) {

    public static final MessageReceipts NONE = new MessageReceipts(null, null);
}
