package com.naro.chat.application.dto;

/**
 * Não lidas do usuário autenticado: mensagens de outras pessoas criadas
 * depois da última leitura de cada conversa.
 *
 * @param conversations conversas com ao menos uma não lida (badge do app)
 * @param messages      total de mensagens não lidas
 */
public record UnreadChatsResponse(

        long conversations,

        long messages

) {
}
