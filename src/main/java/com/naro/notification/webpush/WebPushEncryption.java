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
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

/**
 * Cifra o corpo de uma mensagem Web Push (RFC 8291, "aes128gcm" do RFC 8188).
 *
 * Só o navegador dono da inscrição decifra: a chave vem de um ECDH entre um
 * par efêmero (novo a cada mensagem) e a chave p256dh do navegador, misturado
 * ao segredo auth. O push service (Google, Mozilla, Apple, Microsoft) só vê bytes.
 */
public final class WebPushEncryption {

    /** Um único registro; é o tamanho usado pelos navegadores e pelo exemplo do RFC. */
    static final int RECORD_SIZE = 4096;

    static final int SALT_BYTES = 16;

    static final int AUTH_SECRET_BYTES = 16;

    private static final int TAG_BYTES = 16;

    /** salt (16) + rs (4) + idlen (1) + keyid (65). */
    private static final int HEADER_BYTES = SALT_BYTES + 4 + 1 + EcKeys.PUBLIC_KEY_BYTES;

    /** Push services aceitam até 4096 bytes de corpo, cabeçalho incluído. */
    public static final int MAX_PLAINTEXT_BYTES = RECORD_SIZE - HEADER_BYTES - TAG_BYTES - 1;

    private static final byte[] KEY_INFO = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] CEK_INFO = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] NONCE_INFO = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);

    /** Delimitador do último (e único) registro. */
    private static final byte LAST_RECORD = 0x02;

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushEncryption() {
    }

    /**
     * @param userAgentPublicKey chave p256dh da inscrição (65 bytes)
     * @param authSecret         segredo auth da inscrição (16 bytes)
     */
    public static byte[] encrypt(byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        return encrypt(plaintext, userAgentPublicKey, authSecret, ephemeralKeyPair(), salt);
    }

    /** Determinístico (par efêmero e salt fixos): usado pelos vetores de teste do RFC. */
    static byte[] encrypt(byte[] plaintext, byte[] userAgentPublicKey, byte[] authSecret, KeyPair serverKeys, byte[] salt) {
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Push payload is too large");
        }
        if (authSecret.length != AUTH_SECRET_BYTES || salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("Invalid push subscription secret");
        }

        ECPublicKey userAgentKey = EcKeys.publicKey(userAgentPublicKey);
        byte[] serverPublicKey = EcKeys.encode((ECPublicKey) serverKeys.getPublic());

        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(serverKeys.getPrivate());
            agreement.doPhase(userAgentKey, true);
            byte[] ecdhSecret = agreement.generateSecret();

            // RFC 8291, seção 3.4: HKDF-SHA-256 com expansões de um bloco.
            byte[] prkKey = hmac(authSecret, ecdhSecret);
            byte[] ikm = hmac(prkKey, KEY_INFO, userAgentPublicKey, serverPublicKey, new byte[]{1});
            byte[] prk = hmac(salt, ikm);
            byte[] contentKey = Arrays.copyOf(hmac(prk, CEK_INFO, new byte[]{1}), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, NONCE_INFO, new byte[]{1}), 12);

            byte[] record = Arrays.copyOf(plaintext, plaintext.length + 1);
            record[plaintext.length] = LAST_RECORD;

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new GCMParameterSpec(TAG_BYTES * 8, nonce));
            byte[] ciphertext = cipher.doFinal(record);

            return ByteBuffer.allocate(HEADER_BYTES + ciphertext.length)
                    .put(salt)
                    .putInt(RECORD_SIZE)
                    .put((byte) serverPublicKey.length)
                    .put(serverPublicKey)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Web Push encryption failed", exception);
        }
    }

    private static KeyPair ephemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(EcKeys.P256, RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 key generation failed", exception);
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
