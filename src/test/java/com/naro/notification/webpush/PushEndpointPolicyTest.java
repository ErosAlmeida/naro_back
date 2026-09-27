package com.naro.notification.webpush;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PushEndpointPolicyTest {

    private final PushEndpointPolicy policy = new PushEndpointPolicy(List.of(
            "fcm.googleapis.com",
            "updates.push.services.mozilla.com",
            "web.push.apple.com",
            "*.notify.windows.com"
    ));

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/dGVzdA:APA91b",
            "https://updates.push.services.mozilla.com/wpush/v2/gAAAAAB",
            "https://web.push.apple.com/QGXr1jvGOL3S",
            "https://wns2-bn3p.notify.windows.com/w/?token=BQYAAAB",
            "https://FCM.googleapis.com:443/fcm/send/abc"
    })
    void acceptsTheBrowserPushServices(String endpoint) {
        assertThat(policy.isAllowed(endpoint)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // SSRF: metadata da AWS, rede interna, o próprio servidor
            "https://169.254.169.254/latest/meta-data",
            "http://169.254.169.254/latest/meta-data",
            "https://localhost:8080/api",
            "https://127.0.0.1/",
            // Só https, porta padrão, sem credenciais nem fragmento
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://fcm.googleapis.com:8443/fcm/send/abc",
            "https://attacker@fcm.googleapis.com/fcm/send/abc",
            "https://fcm.googleapis.com/fcm/send/abc#x",
            // Parecidos, mas não são os push services
            "https://fcm.googleapis.com.evil.test/abc",
            "https://evilfcm.googleapis.com/abc",
            "https://notify.windows.com/abc",
            "https://notify.windows.com.evil.test/abc",
            "ftp://fcm.googleapis.com/abc",
            "not a url",
            ""
    })
    void refusesEverythingElse(String endpoint) {
        assertThat(policy.isAllowed(endpoint)).isFalse();
    }

    @Test
    void refusesNullAndOversizedEndpoints() {
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.isAllowed("https://fcm.googleapis.com/" + "a".repeat(PushEndpointPolicy.MAX_ENDPOINT_LENGTH))).isFalse();
    }
}
