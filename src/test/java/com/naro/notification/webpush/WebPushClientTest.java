package com.naro.notification.webpush;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Um push service de mentira em localhost. A lista de hosts permitidos é
 * aplicada antes (no cadastro e no PushNotificationService), não aqui.
 */
class WebPushClientTest {

    private HttpServer server;

    private final AtomicInteger status = new AtomicInteger(201);

    private final AtomicReference<Request> received = new AtomicReference<>();

    private final String[] vapidKeys = WebPushTestSupport.vapidKeys();

    private final WebPushClient client = new WebPushClient(
            HttpClient.newHttpClient(),
            VapidCredentials.of(vapidKeys[0], vapidKeys[1], "mailto:ops@naro.test")
    );

    private record Request(String method, String authorization, String encoding, String contentType,
                           String ttl, String urgency, byte[] body) {
    }

    @BeforeEach
    void startPushService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push/", exchange -> {
            received.set(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Encoding"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("TTL"),
                    exchange.getRequestHeaders().getFirst("Urgency"),
                    exchange.getRequestBody().readAllBytes()
            ));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopPushService() {
        server.stop(0);
    }

    @Test
    void postsAnEncryptedMessageThatOnlyTheBrowserCanRead() {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        byte[] payload = "{\"type\":\"CHAT_MESSAGE\"}".getBytes(StandardCharsets.UTF_8);

        PushDeliveryResult result = client.send(endpoint(), browser.p256dh(), browser.auth(), payload, Duration.ofHours(24));

        assertThat(result).isEqualTo(PushDeliveryResult.DELIVERED);
        Request request = received.get();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.encoding()).isEqualTo("aes128gcm");
        assertThat(request.contentType()).isEqualTo("application/octet-stream");
        assertThat(request.ttl()).isEqualTo("86400");
        assertThat(request.urgency()).isEqualTo("high");
        assertThat(request.authorization()).startsWith("vapid t=").endsWith(", k=" + vapidKeys[0]);
        // Nenhum trecho do JSON viaja em claro.
        assertThat(new String(request.body(), StandardCharsets.ISO_8859_1)).doesNotContain("CHAT_MESSAGE");
        assertThat(browser.decrypt(request.body())).isEqualTo(payload);
    }

    @ParameterizedTest
    @CsvSource({
            "201, DELIVERED",
            "202, DELIVERED",
            "404, EXPIRED",
            "410, EXPIRED",
            "400, REJECTED",
            "403, REJECTED",
            "413, FAILED",
            "429, FAILED",
            "500, FAILED",
            "503, FAILED"
    })
    void translatesThePushServiceAnswer(int answer, PushDeliveryResult expected) {
        status.set(answer);
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        assertThat(client.send(endpoint(), browser.p256dh(), browser.auth(), new byte[]{1}, Duration.ofMinutes(1)))
                .isEqualTo(expected);
    }

    @Test
    void anUnreachablePushServiceIsATransientFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        assertThat(client.send("http://127.0.0.1:" + closedPort + "/push/x", browser.p256dh(), browser.auth(), new byte[]{1}, Duration.ZERO))
                .isEqualTo(PushDeliveryResult.FAILED);
    }

    @Test
    void disabledWithoutVapidKeys() {
        WebPushClient disabled = new WebPushClient(HttpClient.newHttpClient(), null);
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();

        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.publicKey()).isEmpty();
        assertThatThrownBy(() -> disabled.send(endpoint(), browser.p256dh(), browser.auth(), new byte[]{1}, Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
        assertThat(client.publicKey()).contains(vapidKeys[0]);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/push/subscription-1";
    }
}
