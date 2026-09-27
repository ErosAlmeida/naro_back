package com.naro.encryption.application.service;

import com.naro.exception.E2eeError;
import com.naro.exception.E2eeException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Formato do material criptográfico aceito pelo servidor (versão 1).
 *
 * O servidor só confere o formato: tamanhos, Base64 e versão. Ele não
 * tem chave para decifrar nada e nunca tenta. A integridade de verdade
 * é do AES-GCM, verificada no browser de quem lê.
 */
public final class E2eeFormat {

    public static final int VERSION = 1;

    /** Cifra da Master Key, da chave privada e das mensagens. */
    public static final String KEY_WRAP_ALGORITHM = "AES-GCM";

    public static final String IDENTITY_ALGORITHM = "ECDH-P256";

    public static final int IV_BYTES = 12;

    public static final int GCM_TAG_BYTES = 16;

    public static final int MASTER_KEY_BYTES = 32;

    public static final int MASTER_KEY_PROOF_BYTES = 32;

    /** Ponto P-256 não comprimido: 0x04 || X || Y. */
    public static final int PUBLIC_KEY_BYTES = 65;

    /** Limite do WebAuthn para o id de uma credencial. */
    public static final int MAX_CREDENTIAL_ID_BYTES = 1023;

    private static final int MAX_WRAPPED_PRIVATE_KEY_BYTES = 512;

    private E2eeFormat() {
    }

    public static void requireIv(String iv) {
        requireLength(decodeBase64(iv), IV_BYTES);
    }

    /** 32 bytes da Master Key + 16 da tag GCM. */
    public static void requireWrappedMasterKey(String wrappedMasterKey) {
        requireLength(decodeBase64(wrappedMasterKey), MASTER_KEY_BYTES + GCM_TAG_BYTES);
    }

    public static void requireWrappedPrivateKey(String wrappedPrivateKey) {
        int length = decodeBase64(wrappedPrivateKey).length;

        if (length <= GCM_TAG_BYTES || length > MAX_WRAPPED_PRIVATE_KEY_BYTES) {
            throw invalid();
        }
    }

    public static void requirePublicKey(String publicKey) {
        byte[] point = decodeBase64(publicKey);

        if (point.length != PUBLIC_KEY_BYTES || point[0] != 0x04) {
            throw invalid();
        }
    }

    public static void requireCredentialId(String credentialId) {
        byte[] decoded;

        try {
            decoded = Base64.getUrlDecoder().decode(credentialId);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }

        if (decoded.length == 0 || decoded.length > MAX_CREDENTIAL_ID_BYTES) {
            throw invalid();
        }
    }

    public static void requireVersion(Integer version) {
        if (version == null || version != VERSION) {
            throw invalid();
        }
    }

    public static void requireAlgorithm(String algorithm, String expected) {
        if (!expected.equals(algorithm)) {
            throw invalid();
        }
    }

    /**
     * Guardado no banco no lugar da prova. A prova tem 256 bits de
     * entropia (HMAC), então um SHA-256 simples basta.
     */
    public static String hashMasterKeyProof(String masterKeyProof) {
        byte[] proof = decodeBase64(masterKeyProof);
        requireLength(proof, MASTER_KEY_PROOF_BYTES);
        return Base64.getEncoder().encodeToString(sha256(proof));
    }

    /** Comparação em tempo constante; prova ausente ou malformada é inválida. */
    public static boolean matchesMasterKeyProof(String masterKeyProof, String storedHash) {
        if (masterKeyProof == null || storedHash == null) {
            return false;
        }

        try {
            byte[] proof = Base64.getDecoder().decode(masterKeyProof);
            return proof.length == MASTER_KEY_PROOF_BYTES
                    && MessageDigest.isEqual(sha256(proof), Base64.getDecoder().decode(storedHash));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static byte[] decodeBase64(String value) {
        if (value == null) {
            throw invalid();
        }

        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void requireLength(byte[] value, int expected) {
        if (value.length != expected) {
            throw invalid();
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
        }
    }

    private static E2eeException invalid() {
        return new E2eeException(E2eeError.INVALID_PAYLOAD);
    }
}
