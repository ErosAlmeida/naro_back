package com.naro.notification.webpush;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VapidCredentialsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String[] keys = WebPushTestSupport.vapidKeys();

    @Test
    void signsAShortLivedJwtForThePushServiceOrigin() throws Exception {
        VapidCredentials vapid = VapidCredentials.of(keys[0], keys[1], "mailto:ops@naro.test");
        Instant now = Instant.parse("2026-09-27T12:00:00Z");

        String header = vapid.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc"), now);

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys[0]);

        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        JsonNode jwtHeader = JSON.readTree(EcKeys.decodeBase64Url(parts[0]));
        JsonNode claims = JSON.readTree(EcKeys.decodeBase64Url(parts[1]));

        assertThat(jwtHeader.get("alg").asString()).isEqualTo("ES256");
        assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("sub").asString()).isEqualTo("mailto:ops@naro.test");
        assertThat(claims.get("exp").asLong()).isEqualTo(now.plus(VapidCredentials.TOKEN_LIFETIME).getEpochSecond());

        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(EcKeys.publicKey(EcKeys.decodeBase64Url(keys[0])));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(EcKeys.decodeBase64Url(parts[2]))).isTrue();
    }

    @Test
    void reusesTheTokenPerPushServiceUntilCloseToExpiry() {
        VapidCredentials vapid = VapidCredentials.of(keys[0], keys[1], "https://naro.test");
        URI apple = URI.create("https://web.push.apple.com/abc");
        URI mozilla = URI.create("https://updates.push.services.mozilla.com/wpush/v2/abc");
        Instant now = Instant.parse("2026-09-27T12:00:00Z");

        String first = vapid.authorization(apple, now);

        assertThat(vapid.authorization(apple, now.plusSeconds(3600))).isEqualTo(first);
        assertThat(vapid.authorization(mozilla, now)).isNotEqualTo(first);
        assertThat(vapid.authorization(apple, now.plus(VapidCredentials.TOKEN_LIFETIME))).isNotEqualTo(first);
    }

    @Test
    void refusesKeysThatAreNotAPair() {
        String[] other = WebPushTestSupport.vapidKeys();

        assertThatThrownBy(() -> VapidCredentials.of(keys[0], other[1], "mailto:ops@naro.test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(other[1]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ops@naro.test", "http://naro.test", "mailto: ops@naro.test", "https://naro.test\nX: y"})
    void requiresAMailtoOrHttpsSubject(String subject) {
        assertThatThrownBy(() -> VapidCredentials.of(keys[0], keys[1], subject))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void neverShowsThePrivateKey() {
        VapidCredentials vapid = VapidCredentials.of(keys[0], keys[1], "mailto:ops@naro.test");

        assertThat(vapid.toString()).doesNotContain(keys[1]).doesNotContain(keys[0]);
        assertThatThrownBy(() -> VapidCredentials.of(keys[0], "not-a-key!", "mailto:ops@naro.test"))
                .hasMessageNotContaining("not-a-key");
    }

    @Test
    void audienceKeepsOnlyNonDefaultPorts() {
        assertThat(VapidCredentials.audienceOf(URI.create("https://Push.Example.com/a/b?c"))).isEqualTo("https://push.example.com");
        assertThat(VapidCredentials.audienceOf(URI.create("https://push.example.com:8443/a"))).isEqualTo("https://push.example.com:8443");
    }
}
