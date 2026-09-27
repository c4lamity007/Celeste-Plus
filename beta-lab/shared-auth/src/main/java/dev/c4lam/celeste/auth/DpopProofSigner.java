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
public final class DpopProofSigner {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final byte[] SPKI = HexFormat.of().parseHex("302a300506032b6570032100");
    private final PrivateKey privateKey;
    private final Clock clock;
    private final String header;
    private final String thumbprint;

    public DpopProofSigner(PrivateKey privateKey, byte[] publicKey) throws GeneralSecurityException {
        this(privateKey, publicKey, Clock.systemUTC());
    }

    public DpopProofSigner(PrivateKey privateKey, byte[] publicKey, Clock clock) throws GeneralSecurityException {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
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
        if (method == null || !method.matches("[A-Z]{3,16}")) throw new GeneralSecurityException("Invalid DPoP method");
        byte[] unique = new byte[24];
        RANDOM.nextBytes(unique);
        JsonObject claims = new JsonObject();
        claims.addProperty("jti", BASE64.encodeToString(unique));
        claims.addProperty("htm", method);
        claims.addProperty("htu", target(uri));
        claims.addProperty("iat", clock.instant().getEpochSecond());
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

    private byte[] sign(byte[] bytes) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(privateKey);
        signature.update(bytes);
        return signature.sign();
    }

    private static String hash(String text) throws GeneralSecurityException {
        return BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));
    }
}
