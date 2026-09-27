package com.naro.notification.webpush;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.util.Arrays;
import java.util.Base64;

/**
 * Chaves P-256 no formato do Web Push: ponto público não comprimido
 * (65 bytes, 0x04 || X || Y) e escalar privado de 32 bytes, em Base64url.
 * É o formato de PushSubscription.getKey('p256dh') e das chaves VAPID.
 */
public final class EcKeys {

    public static final int PUBLIC_KEY_BYTES = 65;

    static final int COORDINATE_BYTES = 32;

    static final ECParameterSpec P256 = p256();

    private EcKeys() {
    }

    /** Aceita Base64url com ou sem padding; qualquer outro texto é recusado. */
    public static byte[] decodeBase64Url(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing Base64url value");
        }
        return Base64.getUrlDecoder().decode(value.strip());
    }

    public static String encodeBase64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /**
     * Ponto público recebido de fora (navegador ou configuração): confere
     * tamanho, prefixo e se o ponto está na curva antes de qualquer ECDH.
     */
    public static ECPublicKey publicKey(byte[] uncompressedPoint) {
        if (uncompressedPoint.length != PUBLIC_KEY_BYTES || uncompressedPoint[0] != 0x04) {
            throw new IllegalArgumentException("Expected an uncompressed P-256 point");
        }

        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1, 1 + COORDINATE_BYTES));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressedPoint, 1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES));

        if (!isOnCurve(x, y)) {
            throw new IllegalArgumentException("Point is not on the P-256 curve");
        }

        try {
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Invalid P-256 public key", exception);
        }
    }

    public static ECPrivateKey privateKey(byte[] scalar) {
        BigInteger s = new BigInteger(1, scalar);

        if (scalar.length != COORDINATE_BYTES || s.signum() == 0 || s.compareTo(P256.getOrder()) >= 0) {
            throw new IllegalArgumentException("Expected a 32-byte P-256 private key");
        }

        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new ECPrivateKeySpec(s, P256));
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Invalid P-256 private key", exception);
        }
    }

    public static byte[] encode(ECPublicKey key) {
        byte[] encoded = new byte[PUBLIC_KEY_BYTES];
        encoded[0] = 0x04;
        writeCoordinate(key.getW().getAffineX(), encoded, 1);
        writeCoordinate(key.getW().getAffineY(), encoded, 1 + COORDINATE_BYTES);
        return encoded;
    }

    /** y² = x³ + ax + b (mod p), com as coordenadas dentro do corpo. */
    static boolean isOnCurve(BigInteger x, BigInteger y) {
        EllipticCurve curve = P256.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();

        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }

        BigInteger left = y.modPow(BigInteger.TWO, p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);

        return left.equals(right);
    }

    private static void writeCoordinate(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        // BigInteger pode trazer um zero de sinal à esquerda, ou menos de 32 bytes.
        int length = Math.min(bytes.length, COORDINATE_BYTES);
        System.arraycopy(bytes, bytes.length - length, target, offset + COORDINATE_BYTES - length, length);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("P-256 is not available in this JVM", exception);
        }
    }
}
