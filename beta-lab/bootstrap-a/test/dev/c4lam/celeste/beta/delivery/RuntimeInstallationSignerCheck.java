package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import dev.c4lam.celeste.auth.DpopProofSigner;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/** Synthetic certificate/credential checks; never opens a real credential or invokes Windows DPAPI. */
public final class RuntimeInstallationSignerCheck {
    private static int checks;
    private RuntimeInstallationSignerCheck() { }

    public static void run() throws Exception {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000), ZoneOffset.UTC);
        KeyPair server = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair installation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair foreign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String device = "00000000-0000-0000-0000-000000000042";
        JsonObject certificate = new JsonObject();
        certificate.addProperty("aud", "celeste-installation"); certificate.addProperty("sub", "42");
        certificate.addProperty("device", device); certificate.addProperty("install_key", encode(raw(installation)));
        certificate.addProperty("iat", clock.instant().getEpochSecond() - 30);
        certificate.addProperty("exp", clock.instant().getEpochSecond() + 600);
        JsonObject credential = new JsonObject();
        credential.addProperty("deviceId", device); credential.addProperty("uid", 42);
        credential.addProperty("signingPrivate", encode(installation.getPrivate().getEncoded()));
        credential.addProperty("certificate", sign(server, certificate));
        DpopProofSigner expected = new DpopProofSigner(installation.getPrivate(), raw(installation), clock);
        check(parse(credential, server, device, clock).thumbprint().equals(expected.thumbprint()), "legacy signed certificate and PKCS8 accepted");
        credential.addProperty("signingPrivate", encode(((EdECPrivateKey) installation.getPrivate()).getBytes().orElseThrow()));
        check(parse(credential, server, device, clock).thumbprint().equals(expected.thumbprint()), "native seed encoding accepted");
        JsonObject binding = new JsonObject(); binding.addProperty("jkt", expected.thumbprint()); certificate.add("cnf", binding);
        credential.addProperty("certificate", sign(server, certificate));
        check(parse(credential, server, device, clock).thumbprint().equals(expected.thumbprint()), "new certificate confirmation accepted");
        List<Consumer<JsonObject>> changes = List.of(
            c -> c.addProperty("device", "other-device"), c -> c.addProperty("sub", "7"),
            c -> c.addProperty("sub", 42), c -> c.addProperty("sub", "042"),
            c -> c.addProperty("aud", "celeste-installer"), c -> c.addProperty("exp", clock.instant().getEpochSecond()),
            c -> c.addProperty("iat", clock.instant().getEpochSecond() + 6),
            c -> c.addProperty("install_key", encode(raw(foreign))),
            c -> c.getAsJsonObject("cnf").addProperty("jkt", "A".repeat(43)));
        for (Consumer<JsonObject> change : changes) {
            JsonObject changed = certificate.deepCopy(); change.accept(changed);
            JsonObject saved = credential.deepCopy(); saved.addProperty("certificate", sign(server, changed));
            reject(() -> parse(saved, server, device, clock));
        }
        JsonObject badSignature = credential.deepCopy(); badSignature.addProperty("certificate", sign(foreign, certificate));
        reject(() -> parse(badSignature, server, device, clock));
        JsonObject mismatchedPrivate = credential.deepCopy(); mismatchedPrivate.addProperty("signingPrivate", encode(foreign.getPrivate().getEncoded()));
        reject(() -> parse(mismatchedPrivate, server, device, clock));
        JsonObject wrongUid = credential.deepCopy(); wrongUid.addProperty("uid", "42");
        reject(() -> parse(wrongUid, server, device, clock));
        reject(() -> parse(credential, server, "wrong-device", clock));
        reject(() -> RuntimeInstallationSigner.decode(new byte[1024 * 1024 + 1], raw(server), device, 42, clock));
        String duplicate = "{\"uid\":42," + credential.toString().substring(1);
        reject(() -> RuntimeInstallationSigner.decode(duplicate.getBytes(StandardCharsets.UTF_8), raw(server), device, 42, clock));
        System.out.println("PASS protected runtime signer: " + checks + " synthetic certificate/key/identity checks; no real credential access");
    }

    private static DpopProofSigner parse(JsonObject credential, KeyPair server, String device, Clock clock) throws Exception {
        return RuntimeInstallationSigner.decode(credential.toString().getBytes(StandardCharsets.UTF_8), raw(server), device, 42, clock);
    }
    private static byte[] raw(KeyPair key) {
        byte[] encoded = key.getPublic().getEncoded(); return Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
    }
    private static String sign(KeyPair server, JsonObject certificate) throws Exception {
        String body = encode("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}".getBytes(StandardCharsets.US_ASCII))
            + "." + encode(certificate.toString().getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("Ed25519"); signature.initSign(server.getPrivate());
        signature.update(body.getBytes(StandardCharsets.US_ASCII)); return body + "." + encode(signature.sign());
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static void reject(Action action) throws Exception {
        try { action.run(); } catch (GeneralSecurityException expected) { checks++; return; }
        throw new AssertionError("Invalid installation was accepted");
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
