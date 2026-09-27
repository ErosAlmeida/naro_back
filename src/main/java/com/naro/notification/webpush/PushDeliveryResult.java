package com.naro.notification.webpush;

/**
 * O que a resposta do push service diz sobre a inscrição.
 */
public enum PushDeliveryResult {

    /** 2xx: o push service aceitou; o navegador recebe quando estiver online. */
    DELIVERED,

    /** 404/410: a inscrição expirou ou foi cancelada no navegador. Nunca mais vai funcionar. */
    EXPIRED,

    /**
     * 400/401/403: o push service recusa a inscrição com as nossas credenciais
     * (ex.: criada com outra chave VAPID). Só volta a valer quando o navegador
     * se inscrever de novo.
     */
    REJECTED,

    /** Rede, timeout, 413, 429, 5xx: não diz nada sobre a inscrição; tenta na próxima mensagem. */
    FAILED;

    static PushDeliveryResult fromStatus(int status) {
        if (status >= 200 && status < 300) {
            return DELIVERED;
        }
        return switch (status) {
            case 404, 410 -> EXPIRED;
            case 400, 401, 403 -> REJECTED;
            default -> FAILED;
        };
    }
}
