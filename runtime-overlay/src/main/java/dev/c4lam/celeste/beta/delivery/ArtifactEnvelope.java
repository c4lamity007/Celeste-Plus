package dev.c4lam.celeste.beta.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One ephemeral recipient per artifact. Authenticated plaintext is retained only in memory. */
final class ArtifactEnvelope implements AutoCloseable {
    static final int OVERHEAD = 6 + 32 + 12 + 16;
    private static final byte[] MAGIC = "CSTAE1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] X25519 = HexFormat.of().parseHex("302a300506032b656e032100");
    private final KeyPair pair;
    private final String publicKey, nonce;
    ArtifactEnvelope() throws GeneralSecurityException { this(KeyPairGenerator.getInstance("X25519").generateKeyPair(), randomNonce()); }
    ArtifactEnvelope(KeyPair pair, String nonce) {
        this.pair = pair; this.nonce = nonce;
        byte[] encoded = pair.getPublic().getEncoded();
        publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }
    private static String randomNonce() {
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(random); Arrays.fill(random, (byte) 0); return value;
    }
    @Override public void close() { try { pair.getPrivate().destroy(); } catch (javax.security.auth.DestroyFailedException ignored) { } }
    String publicKey() { return publicKey; }
    String nonce() { return nonce; }
    byte[] open(byte[] envelope, String path, String installer, int expectedSize) throws GeneralSecurityException {
        if (envelope.length != expectedSize + OVERHEAD || !Arrays.equals(Arrays.copyOf(envelope, MAGIC.length), MAGIC))
            throw new GeneralSecurityException("Encrypted artifact envelope is invalid");
        byte[] encoded = Arrays.copyOf(X25519, X25519.length + 32);
        System.arraycopy(envelope, 6, encoded, X25519.length, 32);
        KeyAgreement agreement = KeyAgreement.getInstance("X25519"); agreement.init(pair.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded)), true);
        byte[] shared = agreement.generateSecret(), key = null, prk = null;
        byte[] info = ("celeste-artifact-v1\n" + path + "\n" + nonce + "\n" + publicKey).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(installer.getBytes(StandardCharsets.US_ASCII)), "HmacSHA256"));
            prk = mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk, "HmacSHA256")); mac.update(info); key = mac.doFinal(new byte[] {1});
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, envelope, 38, 12));
            cipher.updateAAD(info);
            return cipher.doFinal(envelope, 50, envelope.length - 50);
        } finally {
            Arrays.fill(shared, (byte) 0); if (prk != null) Arrays.fill(prk, (byte) 0); if (key != null) Arrays.fill(key, (byte) 0);
            close();
        }
    }
}
