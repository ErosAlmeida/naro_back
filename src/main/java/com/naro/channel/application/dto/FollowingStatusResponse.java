package com.naro.channel.application.dto;

/**
 * Relação do canal ativo com outro canal. {@code favorite} só é verdadeiro
 * quando {@code following} também é; o endpoint só responde ao dono do canal.
 */
public record FollowingStatusResponse(boolean following, boolean favorite) {
}
