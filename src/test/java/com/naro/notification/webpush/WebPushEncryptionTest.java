package com.naro.notification.webpush;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebPushEncryptionTest {

    /*
     * Exemplo publicado no RFC 8291, seção 5 (chaves de demonstração do
     * próprio RFC, sem valor fora deste teste).
     */
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String SERVER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SERVER_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String BROWSER_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String BROWSER_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String EXPECTED_BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void producesExactlyTheRfc8291Example() {
        KeyPair server = new KeyPair(
                EcKeys.publicKey(EcKeys.decodeBase64Url(SERVER_PUBLIC)),
                EcKeys.privateKey(EcKeys.decodeBase64Url(SERVER_PRIVATE))
        );

        byte[] body = WebPushEncryption.encrypt(
                PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                EcKeys.decodeBase64Url(BROWSER_PUBLIC),
                EcKeys.decodeBase64Url(AUTH_SECRET),
                server,
                EcKeys.decodeBase64Url(SALT)
        );

        assertThat(EcKeys.encodeBase64Url(body)).isEqualTo(EXPECTED_BODY);
    }

    @Test
    void theRfcBrowserDecryptsTheExample() {
        byte[] plaintext = WebPushTestSupport.decrypt(
                EcKeys.decodeBase64Url(EXPECTED_BODY),
                EcKeys.privateKey(EcKeys.decodeBase64Url(BROWSER_PRIVATE)),
                EcKeys.decodeBase64Url(BROWSER_PUBLIC),
                EcKeys.decodeBase64Url(AUTH_SECRET)
        );

        assertThat(new String(plaintext, StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
    }

    @Test
    void onlyTheSubscribedBrowserCanReadTheMessage() {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        WebPushTestSupport.Browser stranger = WebPushTestSupport.browser();
        byte[] payload = "{\"type\":\"CHAT_MESSAGE\",\"preview\":\"Você vai entrar hoje? 🎮\"}".getBytes(StandardCharsets.UTF_8);

        byte[] body = WebPushEncryption.encrypt(payload, EcKeys.decodeBase64Url(browser.p256dh()), EcKeys.decodeBase64Url(browser.auth()));

        assertThat(browser.decrypt(body)).isEqualTo(payload);
        assertThatThrownBy(() -> WebPushTestSupport.decrypt(body, (ECPrivateKey) stranger.keys().getPrivate(),
                EcKeys.decodeBase64Url(browser.p256dh()), EcKeys.decodeBase64Url(browser.auth())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyMessageUsesAFreshKeyAndSalt() {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        byte[] payload = "igual".getBytes(StandardCharsets.UTF_8);

        byte[] first = WebPushEncryption.encrypt(payload, EcKeys.decodeBase64Url(browser.p256dh()), EcKeys.decodeBase64Url(browser.auth()));
        byte[] second = WebPushEncryption.encrypt(payload, EcKeys.decodeBase64Url(browser.p256dh()), EcKeys.decodeBase64Url(browser.auth()));

        assertThat(first).isNotEqualTo(second);
        assertThat(browser.decrypt(first)).isEqualTo(browser.decrypt(second));
    }

    @Test
    void refusesPayloadsThatDoNotFitAPushMessage() {
        WebPushTestSupport.Browser browser = WebPushTestSupport.browser();
        byte[] p256dh = EcKeys.decodeBase64Url(browser.p256dh());
        byte[] auth = EcKeys.decodeBase64Url(browser.auth());

        byte[] largest = new byte[WebPushEncryption.MAX_PLAINTEXT_BYTES];
        assertThat(WebPushEncryption.encrypt(largest, p256dh, auth)).hasSize(4096);

        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[WebPushEncryption.MAX_PLAINTEXT_BYTES + 1], p256dh, auth))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesKeysThatAreNotAValidP256Point() {
        byte[] notOnCurve = EcKeys.decodeBase64Url(BROWSER_PUBLIC);
        notOnCurve[64] ^= 1;
        byte[] auth = EcKeys.decodeBase64Url(AUTH_SECRET);

        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[1], notOnCurve, auth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[1], new byte[33], auth))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushEncryption.encrypt(new byte[1], EcKeys.decodeBase64Url(BROWSER_PUBLIC), new byte[8]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
