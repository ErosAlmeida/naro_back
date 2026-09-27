package com.naro.notification.webpush;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Entrega uma mensagem a um push service (RFC 8030), cifrada para o
 * navegador (RFC 8291) e assinada com VAPID (RFC 8292).
 *
 * Sem chaves VAPID configuradas o Web Push fica desligado: nada é enviado
 * e o resto do Naro funciona igual.
 *
 * Logs só com host e status: o endpoint é uma URL-segredo e o corpo é
 * conteúdo da conversa.
 */
public class WebPushClient {

    private static final Logger log = LoggerFactory.getLogger(WebPushClient.class);

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    private final VapidCredentials vapid;

    /** @param vapid null quando o Web Push não está configurado */
    public WebPushClient(HttpClient httpClient, VapidCredentials vapid) {
        this.httpClient = httpClient;
        this.vapid = vapid;
    }

    public boolean isEnabled() {
        return vapid != null;
    }

    /** applicationServerKey para o navegador; vazio com o Web Push desligado. */
    public Optional<String> publicKey() {
        return Optional.ofNullable(vapid).map(VapidCredentials::publicKey);
    }

    /**
     * Nunca lança por falha de rede ou do push service: o resultado diz o
     * que fazer com a inscrição.
     *
     * @param p256dh chave pública do navegador (Base64url)
     * @param auth   segredo de autenticação do navegador (Base64url)
     */
    public PushDeliveryResult send(String endpoint, String p256dh, String auth, byte[] payload, Duration ttl) {
        if (vapid == null) {
            throw new IllegalStateException("Web Push is not configured");
        }

        URI uri = URI.create(endpoint);
        byte[] body = WebPushEncryption.encrypt(payload, EcKeys.decodeBase64Url(p256dh), EcKeys.decodeBase64Url(auth));

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", vapid.authorization(uri, Instant.now()))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", Long.toString(Math.max(0, ttl.toSeconds())))
                // Mensagem de chat: o aparelho pode acordar para mostrar.
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            PushDeliveryResult result = PushDeliveryResult.fromStatus(response.statusCode());

            if (result != PushDeliveryResult.DELIVERED) {
                log.info("Push service {} answered {} ({})", uri.getHost(), response.statusCode(), result);
            }

            return result;
        } catch (IOException exception) {
            log.warn("Push service {} unreachable: {}", uri.getHost(), exception.getClass().getSimpleName());
            return PushDeliveryResult.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return PushDeliveryResult.FAILED;
        }
    }
}
