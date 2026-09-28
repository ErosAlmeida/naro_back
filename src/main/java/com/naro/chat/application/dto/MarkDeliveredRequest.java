package com.naro.chat.application.dto;

import java.util.UUID;

/**
 * Corpo de POST /api/v1/chats/{conversationId}/delivered (service worker,
 * quando o push chega). Sem messageId (ou sem corpo), vale a conversa toda.
 */
public record MarkDeliveredRequest(

        UUID messageId

) {
}
