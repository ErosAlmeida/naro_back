package com.naro.chat.application.dto;

import java.util.UUID;

/**
 * Enviado pelo WebSocket (/app/chat/focus) quando esta aba passa a mostrar
 * uma conversa na tela (visível e em foco) ou deixa de mostrar (null).
 *
 * Não carrega identidade: vale para o usuário e a sessão da conexão. O
 * conversationId só é aceito se ele participa da conversa; ele só afeta os
 * próprios avisos (não recebe push dessa conversa) e marca a conversa como lida.
 */
public record ChatFocusRequest(

        UUID conversationId

) {
}
