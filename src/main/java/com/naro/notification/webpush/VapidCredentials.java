package com.naro.notification.webpush;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Identificação do servidor perante os push services (VAPID, RFC 8292).
 *
 * A chave pública vai para o navegador (applicationServerKey); a privada só
 * assina, aqui, o JWT curto do cabeçalho Authorization. Nenhum método expõe
 * ou registra a chave privada, e toString() não mostra nada das chaves.
 */
public final class VapidCredentials {

    /** O RFC limita a 24h; 12h deixa folga para relógios diferentes. */
    static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    /** Um token é reaproveitado enquanto faltar mais que isto para vencer. */
    static final Duration TOKEN_MIN_REMAINING = Duration.ofHours(1);

    private static final String JWT_HEADER = EcKeys.encodeBase64Url(
            "{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));

    private final ECPrivateKey privateKey;

    private final String publicKey;

    private final String subject;

    private final ConcurrentMap<String, Token> tokens = new ConcurrentHashMap<>();

    private VapidCredentials(ECPrivateKey privateKey, String publicKey, String subject) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.subject = subject;
    }

    /**
     * Confere formato, curva, se as duas chaves são um par e o subject
     * (mailto: ou https:, exigido pela Apple). As mensagens de erro nunca
     * incluem os valores recebidos.
     */
    public static VapidCredentials of(String publicKey, String privateKey, String subject) {
        if (subject == null
                || !(subject.startsWith("mailto:") || subject.startsWith("https://"))
                || subject.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("VAPID subject must be a mailto: or https: URI");
        }

        ECPublicKey ecPublicKey;
        ECPrivateKey ecPrivateKey;

        try {
            ecPublicKey = EcKeys.publicKey(EcKeys.decodeBase64Url(publicKey));
            ecPrivateKey = EcKeys.privateKey(EcKeys.decodeBase64Url(privateKey));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("VAPID keys must be a Base64url P-256 key pair");
        }

        requireKeyPair(ecPublicKey, ecPrivateKey);

        return new VapidCredentials(ecPrivateKey, EcKeys.encodeBase64Url(EcKeys.encode(ecPublicKey)), subject);
    }

    /** Base64url do ponto não comprimido: o applicationServerKey do navegador. */
    public String publicKey() {
        return publicKey;
    }

    /** Valor do cabeçalho Authorization para um endpoint (esquema "vapid", RFC 8292). */
    public String authorization(URI endpoint, Instant now) {
        String audience = audienceOf(endpoint);

        Token token = tokens.compute(audience, (_, current) ->
                current != null && current.expiresAt().minus(TOKEN_MIN_REMAINING).isAfter(now)
                        ? current
                        : new Token(sign(audience, now.plus(TOKEN_LIFETIME)), now.plus(TOKEN_LIFETIME)));

        return "vapid t=" + token.value() + ", k=" + publicKey;
    }

    @Override
    public String toString() {
        return "VapidCredentials[subject=" + subject + "]";
    }

    /** Origem do push service: esquema + host (+ porta, se não for a padrão). */
    static String audienceOf(URI endpoint) {
        String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
        String host = endpoint.getHost().toLowerCase(Locale.ROOT);
        int port = endpoint.getPort();

        return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }

    private String sign(String audience, Instant expiresAt) {
        String claims = "{\"aud\":\"" + json(audience) + "\",\"exp\":" + expiresAt.getEpochSecond()
                + ",\"sub\":\"" + json(subject) + "\"}";
        String signingInput = JWT_HEADER + "." + EcKeys.encodeBase64Url(claims.getBytes(StandardCharsets.UTF_8));

        try {
            // P1363: r || s de 32 bytes cada, o formato do JWS (e não DER).
            Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + EcKeys.encodeBase64Url(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("VAPID signing failed", exception);
        }
    }

    private static void requireKeyPair(ECPublicKey publicKey, ECPrivateKey privateKey) {
        byte[] probe = "naro-vapid-key-pair-check".getBytes(StandardCharsets.US_ASCII);

        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(probe);

            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("VAPID public and private keys do not match");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("VAPID keys must be a Base64url P-256 key pair");
        }
    }

    /** subject e audience são URIs: basta escapar aspas e barras invertidas. */
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Token(String value, Instant expiresAt) {
    }
}
