package com.naro.channel.application.dto;

import java.util.UUID;

/**
 * O canal como autor de uma publicação ou comentário: o necessário para
 * mostrar a autoria e levar até /c/{slug}. A foto vem como URL de leitura
 * (pré-assinada) e é nula quando o canal não tem uma.
 */
public record ChannelSummaryResponse(UUID id, String name, String slug, String profileImageUrl) {
}
