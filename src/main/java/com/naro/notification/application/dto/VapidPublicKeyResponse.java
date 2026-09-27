package com.naro.notification.application.dto;

/**
 * Chave pública VAPID (applicationServerKey do navegador).
 *
 * enabled = false quando o servidor não tem Web Push configurado: o
 * frontend esconde a opção e o Naro funciona igual.
 */
public record VapidPublicKeyResponse(

        boolean enabled,

        String publicKey

) {
}
