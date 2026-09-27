package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

/** Actual JCA signing and independent RFC 8032 public test-vector checks; no live credentials. */
public final class DpopProofCheck {
    private static final Gson JSON = new Gson();
    private static int checks;
    private DpopProofCheck() { }

    public static void main(String[] args) throws Exception {
        byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] publicKey = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        var key = Crypto.ed25519Seed(seed);
        var fixed = new DpopProofSigner(key, publicKey, Clock.fixed(Instant.ofEpochSecond(1700000000), ZoneOffset.UTC));
        check(fixed.thumbprint().equals("kPrK_qmxVWaYVA9wwBF6Iuo3vVzz7TxHCTwXBygrS4k"), "RFC7638 canonical Ed25519 thumbprint");
        URI url = URI.create("https://AUTH.invalid:443/v1/blob/a?ignored=1#ignored");
        String jwt = fixed.proof("GET", url, "public-fixture-token");
        String[] parts = jwt.split("\\.");
        check(parts.length == 3, "compact proof");
        check(Crypto.verify(Crypto.ed25519Public(publicKey), (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), Crypto.b64(parts[2])), "real Ed25519 signature");
        JsonObject header = json(parts[0]), claims = json(parts[1]);
        check(header.get("typ").getAsString().equals("dpop+jwt") && header.get("alg").getAsString().equals("EdDSA"), "typed asymmetric proof");
        check(header.getAsJsonObject("jwk").keySet().equals(java.util.Set.of("crv", "kty", "x")), "public-only JWK");
        check(header.getAsJsonObject("jwk").get("x").getAsString().equals(Crypto.b64(publicKey)), "expected public key");
        check(claims.get("htu").getAsString().equals("https://auth.invalid/v1/blob/a"), "normalized target excludes query/fragment");
        check(claims.get("htm").getAsString().equals("GET") && claims.get("iat").getAsLong() == 1700000000, "method and time");
        check(claims.get("ath").getAsString().equals(Crypto.b64(Crypto.sha256("public-fixture-token".getBytes(StandardCharsets.US_ASCII)))), "hash covers exact token");
        check(Crypto.b64(claims.get("jti").getAsString()).length >= 16, "bounded high-entropy replay identifier");
        var next = json(fixed.proof("GET", url, "public-fixture-token").split("\\.")[1]);
        check(!next.get("jti").equals(claims.get("jti")), "each retry obtains a fresh proof");
        check(!json(fixed.proof("POST", url, null).split("\\.")[1]).has("ath"), "issuance proof omits token hash");
        for (String invalid : new String[]{"http://auth.invalid/", "https://user@auth.invalid/", "file:///tmp/", "https://auth.invalid:0/"}) {
            reject(() -> fixed.proof("GET", URI.create(invalid), "token"), "invalid origin rejected");
        }
        reject(() -> fixed.proof("get", url, "token"), "noncanonical method rejected");
        reject(() -> fixed.proof("GET", url, "token\nsecret"), "invalid token rejected");
        reject(() -> new DpopProofSigner(Crypto.generateEd25519().getPrivate(), publicKey), "mismatched key pair rejected");
        JsonObject bound = new JsonObject(), confirmation = new JsonObject();
        confirmation.addProperty("jkt", fixed.thumbprint()); bound.add("cnf", confirmation);
        fixed.requireBinding(bound); check(true, "matching signed confirmation accepted");
        reject(() -> fixed.requireBinding(new JsonObject()), "missing confirmation rejected");
        confirmation.addProperty("jkt", "wrong"); reject(() -> fixed.requireBinding(bound), "wrong confirmation rejected");
        bound.addProperty("cnf", "malformed"); reject(() -> fixed.requireBinding(bound), "malformed confirmation rejected");

        if (args.length == 1) {
            DpopProofSigner liveClock = new DpopProofSigner(key, publicKey);
            String proof = liveClock.proof("GET", URI.create("https://auth.invalid/v1/blob/a"), "public-fixture-token");
            JsonObject fixture = new JsonObject();
            fixture.addProperty("public_key", Crypto.b64(publicKey));
            fixture.addProperty("token", "public-fixture-token");
            fixture.addProperty("method", "GET"); fixture.addProperty("url", "https://auth.invalid/v1/blob/a");
            fixture.addProperty("proof", proof);
            fixture.addProperty("iat", json(proof.split("\\.")[1]).get("iat").getAsLong());
            Path target = Path.of(args[0]); Files.createDirectories(target.toAbsolutePath().getParent());
            Files.writeString(target, fixture.toString() + "\n", StandardCharsets.UTF_8);
        }
        System.out.println("PASS DPoP signer: " + checks + " checks; synthetic public fixture only");
    }

    private static JsonObject json(String part) { return JSON.fromJson(new String(Crypto.b64(part), StandardCharsets.UTF_8), JsonObject.class); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void reject(Action action, String message) throws Exception {
        try { action.run(); } catch (java.security.GeneralSecurityException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
