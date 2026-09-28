package com.naro.chat.application.dto;

import java.util.UUID;

/**
 * Confirmação de entrega: as mensagens chegaram a um aparelho do usuário
 * (✓✓ para quem enviou). Vem do WebSocket (/app/chat/delivered, com o Naro
 * aberto) ou do service worker (POST /api/v1/chats/{id}/delivered, quando o
 * push chega com o Naro fechado).
 *
 *   conversationId null            tudo o que já existe em todas as conversas
 *                                  (o Naro acabou de abrir ou reconectar)
 *   messageId null                 tudo o que já existe nesta conversa
 *   conversationId + messageId     esta mensagem e as anteriores a ela
 *
 * Não carrega identidade: vale para o usuário da conexão ou do token.
 */
public record ChatDeliveredRequest(

        UUID conversationId,

        UUID messageId

) {
}
