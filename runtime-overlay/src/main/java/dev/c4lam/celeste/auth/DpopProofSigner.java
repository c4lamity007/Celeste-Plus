package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** RFC 9449 request proofs. Instances expose a signing capability, never key material. */
public final class DpopProofSigner implements AutoCloseable {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final byte[] SPKI = HexFormat.of().parseHex("302a300506032b6570032100");
    private byte[] privateEncoding;
    private final Clock clock;
    private final String header;
    private final String thumbprint;

    public DpopProofSigner(PrivateKey privateKey, byte[] publicKey) throws GeneralSecurityException {
        this(privateKey, publicKey, Clock.systemUTC());
    }

    public DpopProofSigner(PrivateKey privateKey, byte[] publicKey, Clock clock) throws GeneralSecurityException {
        this(Objects.requireNonNull(privateKey, "privateKey").getEncoded(), publicKey, clock, true);
    }

    /** Copies the encoding; the caller retains responsibility for its own input buffer. */
    public static DpopProofSigner fromEncoded(byte[] encoded, byte[] publicKey, Clock clock) throws GeneralSecurityException {
        if (encoded == null) throw new GeneralSecurityException("Missing installation key");
        return new DpopProofSigner(encoded.clone(), publicKey, clock, true);
    }

    private DpopProofSigner(byte[] ownedEncoding, byte[] publicKey, Clock clock, boolean owned) throws GeneralSecurityException {
        this.privateEncoding = ownedEncoding;
        try {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (publicKey == null || publicKey.length != 32) throw new GeneralSecurityException("Invalid DPoP public key");
        byte[] encoded = java.util.Arrays.copyOf(SPKI, SPKI.length + 32);
        System.arraycopy(publicKey, 0, encoded, SPKI.length, 32);
        var publicObject = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        Signature check = Signature.getInstance("Ed25519");
        check.initVerify(publicObject);
        check.update(challenge);
        if (!check.verify(sign(challenge))) throw new GeneralSecurityException("DPoP installation keys do not match");
        String x = BASE64.encodeToString(publicKey);
        // RFC 7638/8037: only required JWK members, lexicographic ordering.
        String canonical = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"" + x + "\"}";
        thumbprint = hash(canonical);
        header = BASE64.encodeToString(("{\"typ\":\"dpop+jwt\",\"alg\":\"EdDSA\",\"jwk\":"
            + canonical + "}").getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException | RuntimeException | Error failure) { close(); throw failure; }
    }

    public String thumbprint() { return thumbprint; }

    /** Claims must already have passed the server-signature and purpose/expiry checks. */
    public void requireBinding(JsonObject signedClaims) throws GeneralSecurityException {
        try {
            var confirmation = signedClaims.getAsJsonObject("cnf");
            var value = confirmation == null ? null : confirmation.get("jkt");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !thumbprint.equals(value.getAsString())) {
                throw new GeneralSecurityException("Authorization is not bound to this installation's DPoP key");
            }
        } catch (IllegalStateException | ClassCastException exception) {
            throw new GeneralSecurityException("Malformed DPoP key binding", exception);
        }
    }

    public String proof(String method, URI uri, String accessToken) throws GeneralSecurityException {
        return proof(method, uri, accessToken, null, null, null);
    }

    /** Artifact key/nonce are covered by the same installation proof, never unsigned headers alone. */
    public String proof(String method, URI uri, String accessToken, String deliveryKey, String deliveryNonce) throws GeneralSecurityException {
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, null);
    }

    public String proofForDevice(String method, URI uri, String accessToken, String hardwareDigest) throws GeneralSecurityException {
        if (hardwareDigest == null || !hardwareDigest.matches("[0-9a-f]{64}")) throw new GeneralSecurityException("Invalid device proof binding");
        return proof(method, uri, accessToken, null, null, hardwareDigest);
    }

    public String proofForArtifact(String method, URI uri, String accessToken, String deliveryKey,
                                   String deliveryNonce, String hardwareDigest) throws GeneralSecurityException {
        if (hardwareDigest == null || !hardwareDigest.matches("[0-9a-f]{64}"))
            throw new GeneralSecurityException("Invalid artifact device binding");
        if (deliveryKey == null || deliveryNonce == null) throw new GeneralSecurityException("Missing artifact recipient binding");
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, hardwareDigest);
    }

    /** Incident proofs bind the canonical diagnostic body without module recipient fields. */
    public String proofForIncident(String method, URI uri, String accessToken, String hardwareDigest, byte[] body)
            throws GeneralSecurityException {
        if (hardwareDigest == null || !hardwareDigest.matches("[0-9a-f]{64}")
                || body == null || body.length == 0 || body.length > 8192)
            throw new GeneralSecurityException("Invalid incident request proof binding");
        return proof(method, uri, accessToken, null, null, hardwareDigest,
            BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
    }

    /** Module proofs bind the exact request body, including empty GET requests. */
    public String proofForModule(String method, URI uri, String accessToken, String deliveryKey,
                                 String deliveryNonce, String hardwareDigest, byte[] body) throws GeneralSecurityException {
        if (hardwareDigest == null || !hardwareDigest.matches("[0-9a-f]{64}")
                || deliveryKey == null || deliveryNonce == null || body == null || body.length > 1_048_576)
            throw new GeneralSecurityException("Invalid module request proof binding");
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, hardwareDigest,
            BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
    }

    /** Module-only device evidence is authenticated by both this installation proof and the token key. */
    public String proofForModule(String method, URI uri, String accessToken, String deliveryKey,
                                 String deliveryNonce, String hardwareDigest, byte[] body, byte[] evidence)
            throws GeneralSecurityException {
        if (hardwareDigest == null || !hardwareDigest.matches("[0-9a-f]{64}")
                || deliveryKey == null || deliveryNonce == null || body == null || body.length > 1_048_576
                || evidence == null || evidence.length == 0 || evidence.length > 4096)
            throw new GeneralSecurityException("Invalid module identity evidence");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, hardwareDigest,
            BASE64.encodeToString(hash.digest(body)), BASE64.encodeToString(hash.digest(evidence)));
    }

    private String proof(String method, URI uri, String accessToken, String deliveryKey, String deliveryNonce, String hardwareDigest) throws GeneralSecurityException {
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, hardwareDigest, null);
    }

    private String proof(String method, URI uri, String accessToken, String deliveryKey, String deliveryNonce,
                         String hardwareDigest, String bodyHash) throws GeneralSecurityException {
        return proof(method, uri, accessToken, deliveryKey, deliveryNonce, hardwareDigest, bodyHash, null);
    }

    private String proof(String method, URI uri, String accessToken, String deliveryKey, String deliveryNonce,
                         String hardwareDigest, String bodyHash, String evidenceHash) throws GeneralSecurityException {
        if (method == null || !method.matches("[A-Z]{3,16}")) throw new GeneralSecurityException("Invalid DPoP method");
        byte[] unique = new byte[24];
        RANDOM.nextBytes(unique);
        JsonObject claims = new JsonObject();
        claims.addProperty("jti", BASE64.encodeToString(unique));
        claims.addProperty("htm", method);
        claims.addProperty("htu", target(uri));
        claims.addProperty("iat", clock.instant().getEpochSecond());
        if (deliveryKey != null || deliveryNonce != null) {
            if (deliveryKey == null || deliveryNonce == null || !deliveryKey.matches("[A-Za-z0-9_-]{43}")
                || !deliveryNonce.matches("[A-Za-z0-9_-]{32}")
                || Base64.getUrlDecoder().decode(deliveryKey).length != 32
                || !BASE64.encodeToString(Base64.getUrlDecoder().decode(deliveryKey)).equals(deliveryKey))
                throw new GeneralSecurityException("Invalid artifact proof binding");
            claims.addProperty("cek", deliveryKey);
            claims.addProperty("cnonce", deliveryNonce);
        }
        if (hardwareDigest != null) claims.addProperty("hwid", hardwareDigest);
        if (bodyHash != null) claims.addProperty("bht", bodyHash);
        if (evidenceHash != null) claims.addProperty("meh", evidenceHash);
        if (accessToken != null) {
            if (accessToken.isEmpty() || accessToken.length() > 32768
                || !accessToken.chars().allMatch(c -> c > 32 && c < 127)) {
                throw new GeneralSecurityException("Invalid DPoP access token");
            }
            claims.addProperty("ath", hash(accessToken));
        }
        String input = header + "." + BASE64.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        return input + "." + BASE64.encodeToString(sign(input.getBytes(StandardCharsets.US_ASCII)));
    }

    /** RFC 9449 excludes query/fragment and permits scheme/host/default-port normalization. */
    public static String target(URI uri) throws GeneralSecurityException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || uri.getRawUserInfo() != null || uri.isOpaque() || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new GeneralSecurityException("DPoP requires an HTTPS target without user information");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String port = uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort();
        String path = uri.getRawPath();
        return "https://" + host + port + (path == null || path.isEmpty() ? "/" : path);
    }

    private synchronized byte[] sign(byte[] bytes) throws GeneralSecurityException {
        if (privateEncoding == null) throw new GeneralSecurityException("Installation signer has been closed");
        return CredentialMaterial.signEncoded(privateEncoding, bytes);
    }

    public synchronized boolean isDestroyed() { return privateEncoding == null; }

    @Override public synchronized void close() {
        if (privateEncoding != null) { java.util.Arrays.fill(privateEncoding, (byte) 0); privateEncoding = null; }
    }

    private static String hash(String text) throws GeneralSecurityException {
        return BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
    }
}
