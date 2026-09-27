package com.naro.notification.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON cifrado que o service worker recebe (public/sw.js). Só o necessário
 * para mostrar a notificação e abrir a conversa: nenhum token, e-mail ou id
 * de usuário.
 *
 * O que aparece depende do modo de prévia (naro.push.preview):
 *
 *   NAME_AND_PREVIEW   sender + preview (preview null em mensagem E2EE)
 *   NAME_ONLY          sender, preview null
 *   GENERIC            sender e preview null ("Você recebeu uma nova mensagem")
 *
 * @param url                  caminho relativo da conversa; o service worker
 *                             o recalcula a partir de conversationId
 * @param unreadConversations  conversas com não lidas, para o badge do app
 */
public record PushNotificationPayload(

        String type,

        UUID conversationId,

        UUID messageId,

        Sender sender,

        String preview,

        String url,

        Instant createdAt,

        long unreadConversations

) {

    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";

    public record Sender(

            String displayName,

            String avatarUrl

    ) {
    }
}
