package com.naro.notification.webpush;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * O lado do navegador, só para testes: gera as chaves de uma inscrição
 * (p256dh + auth) e decifra o corpo como o navegador faria (RFC 8291).
 * As chaves são geradas a cada execução; nenhuma fica no código.
 */
public final class WebPushTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushTestSupport() {
    }

    /** Inscrição de um navegador de mentira. */
    public record Browser(KeyPair keys, String p256dh, String auth) {

        public byte[] decrypt(byte[] body) {
            return WebPushTestSupport.decrypt(body, (ECPrivateKey) keys.getPrivate(),
                    EcKeys.decodeBase64Url(p256dh), EcKeys.decodeBase64Url(auth));
        }
    }

    public static Browser browser() {
        KeyPair keys = keyPair();
        byte[] auth = new byte[16];
        RANDOM.nextBytes(auth);
        return new Browser(keys, EcKeys.encodeBase64Url(EcKeys.encode((ECPublicKey) keys.getPublic())), EcKeys.encodeBase64Url(auth));
    }

    /** Par VAPID novo: [chave pública, chave privada], em Base64url. */
    public static String[] vapidKeys() {
        KeyPair keys = keyPair();
        byte[] scalar = ((ECPrivateKey) keys.getPrivate()).getS().toByteArray();
        byte[] fixed = new byte[32];
        int length = Math.min(scalar.length, 32);
        System.arraycopy(scalar, scalar.length - length, fixed, 32 - length, length);
        return new String[]{
                EcKeys.encodeBase64Url(EcKeys.encode((ECPublicKey) keys.getPublic())),
                EcKeys.encodeBase64Url(fixed)
        };
    }

    public static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(EcKeys.P256, RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static byte[] decrypt(byte[] body, ECPrivateKey userAgentPrivateKey, byte[] userAgentPublicKey, byte[] authSecret) {
        ByteBuffer buffer = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        buffer.get(salt);
        int recordSize = buffer.getInt();
        byte[] keyId = new byte[buffer.get()];
        buffer.get(keyId);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        if (recordSize < ciphertext.length) {
            throw new IllegalStateException("Record larger than rs");
        }

        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(userAgentPrivateKey);
            agreement.doPhase(EcKeys.publicKey(keyId), true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hmac(prkKey, "WebPush: info\0".getBytes(StandardCharsets.US_ASCII), userAgentPublicKey, keyId, new byte[]{1});
            byte[] prk = hmac(salt, ikm);
            byte[] contentKey = Arrays.copyOf(hmac(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1}), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[]{1}), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] padded = cipher.doFinal(ciphertext);

            int end = padded.length - 1;
            while (end >= 0 && padded[end] == 0) {
                end--;
            }
            if (end < 0 || padded[end] != 0x02) {
                throw new IllegalStateException("Missing last-record delimiter");
            }
            return Arrays.copyOf(padded, end);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not decrypt push message", exception);
        }
    }

    private static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : parts) {
            mac.update(part);
        }
        return mac.doFinal();
    }
}
