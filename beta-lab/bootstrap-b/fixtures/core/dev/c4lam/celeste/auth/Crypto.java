package dev.c4lam.celeste.auth;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.XECPrivateKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Crypto {
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final byte[] X25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b656e032100");

    private Crypto() {
    }

    static KeyPair generateEd25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static KeyPair generateX25519() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    static byte[] rawPublic(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length < 32) {
            throw new IllegalArgumentException("Invalid public key encoding");
        }
        return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }

    static PublicKey ed25519Public(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(ED25519_SPKI_PREFIX, raw)));
    }

    static PublicKey x25519Public(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(concat(X25519_SPKI_PREFIX, raw)));
    }

    static PrivateKey ed25519Seed(byte[] seed) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePrivate(
            new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)
        );
    }

    static PrivateKey x25519Seed(byte[] seed) throws GeneralSecurityException {
        return KeyFactory.getInstance("X25519").generatePrivate(
            new XECPrivateKeySpec(NamedParameterSpec.X25519, seed)
        );
    }

    static PrivateKey privateKey(String algorithm, byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    static byte[] sign(PrivateKey key, byte[] message) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key);
        signature.update(message);
        return signature.sign();
    }

    static boolean verify(PublicKey key, byte[] message, byte[] signed) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(key);
        signature.update(message);
        return signature.verify(signed);
    }

    static byte[] hkdf(byte[] sharedSecret, byte[] salt, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] pseudoRandomKey = mac.doFinal(sharedSecret);
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        byte[] previous = new byte[0];
        int counter = 1;
        while (output.size() < length) {
            mac.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA256"));
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter++);
            previous = mac.doFinal();
            output.write(previous, 0, Math.min(previous.length, length - output.size()));
        }
        return output.toByteArray();
    }

    static String sha256(String value) {
        return hex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static byte[] b64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
