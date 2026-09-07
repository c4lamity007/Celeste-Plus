package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

/** Offline proofs against a vector emitted by the actual Python server, plus hostile fixtures. */
public final class BetaDeliveryClientCheck {
    private static final URI ORIGIN = URI.create("https://auth.invalid/");
    private static int checks;
    private BetaDeliveryClientCheck() { }

    public static void main(String[] args) throws Exception {
        JsonObject golden = new Gson().fromJson(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8), JsonObject.class);
        Fixture fixture = new Fixture(golden);
        check(fixture.uid == 42, "arbitrary UID42 accepted without Owner UID exemption");
        check(golden.getAsJsonObject("installer_claims").get("product").getAsString().equals("celeste_plus"), "server installer is scoped to Celeste+");
        check(golden.getAsJsonObject("manifest_claims").get("product").getAsString().equals("celeste_plus"), "server manifest is scoped to Celeste+");
        BetaDeliveryClient client = fixture.client();
        BetaDeliveryClient.VerifiedCatalog catalog = client.verifyManifest(fixture.manifestToken);
        check(catalog.catalogSha256().equals(golden.get("catalog_sha256").getAsString()), "actual server catalog hash");
        JsonObject projected = projection(golden.getAsJsonObject("manifest_claims"));
        check(BetaDeliveryClient.canonical(projected).equals(golden.get("canonical_catalog").getAsString()),
            "Python canonical JSON matches, including <>& quote/backslash");
        check(catalog.clientVersion().contains("<>&"), "golden escaping case exercised");
        check(catalog.artifacts().size() == 4, "exact four artifacts");
        catalog = client.fetchCatalog();
        try (BetaDeliveryClient.MemoryRelease release = client.download(catalog)) {
            for (String id : BetaDeliveryClient.IDS) check(Arrays.equals(read(release.artifact(id)), fixture.artifacts.get(id)), "verified memory bytes: " + id);
            rejected(ReadOnlyBufferException.class, () -> release.artifact("payload-a").put((byte) 1));
            rejected(IllegalArgumentException.class, () -> release.artifact("unknown"));
        }
        Fixture closedFixture = new Fixture(golden);
        BetaDeliveryClient closedClient = closedFixture.client();
        BetaDeliveryClient.MemoryRelease closed = closedClient.download(closedClient.verifyManifest(closedFixture.manifestToken));
        ByteBuffer retained = closed.artifact("payload-a");
        closed.close(); closed.close();
        check(allZero(read(retained)), "close zeroes retained views");
        rejected(IllegalStateException.class, () -> closed.artifact("payload-a"));
        BetaDeliveryClient other = fixture.client();
        BetaDeliveryClient.VerifiedCatalog owned = client.verifyManifest(fixture.manifestToken);
        rejected(GeneralSecurityException.class, () -> other.download(owned));

        Synthetic synthetic = new Synthetic(golden);
        BetaDeliveryClient verifier = synthetic.fixture.client();
        int mutationIndex = 0;
        for (Consumer<JsonObject> mutation : manifestMutations()) {
            JsonObject changed = synthetic.manifest.deepCopy(); mutation.accept(changed);
            // Recompute the catalog digest so structural guards, not only digest mismatch, must reject.
            changed.addProperty("catalog_sha256", BetaDeliveryClient.sha256(BetaDeliveryClient.canonical(projection(changed)).getBytes(StandardCharsets.UTF_8)));
            try { rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(synthetic.sign(changed))); }
            catch (AssertionError error) { throw new AssertionError("Manifest mutation " + mutationIndex + " was accepted", error); }
            mutationIndex++;
        }
        JsonObject badDigest = synthetic.manifest.deepCopy(); badDigest.addProperty("catalog_sha256", "0".repeat(64));
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(synthetic.sign(badDigest)));
        for (String bad : List.of("invalid", synthetic.fixture.manifestToken + "=", synthetic.fixture.manifestToken.substring(1)))
            rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(bad));
        String duplicate = BetaDeliveryClient.canonical(synthetic.manifest);
        duplicate = "{\"schema\":\"duplicate\"," + duplicate.substring(1);
        String signedDuplicate = synthetic.signRaw(duplicate);
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(signedDuplicate));
        Synthetic foreign = new Synthetic(golden);
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(foreign.fixture.manifestToken));
        for (Consumer<JsonObject> mutation : installerMutations()) {
            JsonObject changed = synthetic.installer.deepCopy(); mutation.accept(changed);
            String token = synthetic.sign(changed);
            rejected(GeneralSecurityException.class, () -> synthetic.fixture.clientWith(token, synthetic.fixture.uid, synthetic.fixture.device, ORIGIN));
        }
        for (long wrongUid : new long[] { 0, -1, 7, 43, Long.MAX_VALUE })
            rejected(GeneralSecurityException.class, () -> fixture.clientWith(fixture.installerToken, wrongUid, fixture.device, ORIGIN));
        rejected(GeneralSecurityException.class, () -> fixture.clientWith(fixture.installerToken, fixture.uid, "00000000-0000-0000-0000-000000000008", ORIGIN));
        for (String origin : List.of("http://auth.invalid/", "https://other@auth.invalid/", "https://auth.invalid/path", "https://auth.invalid/?q=1", "https://auth.invalid/#x"))
            rejected(GeneralSecurityException.class, () -> fixture.clientWith(fixture.installerToken, fixture.uid, fixture.device, URI.create(origin)));
        for (String failure : List.of("foreign", "redirect", "denied", "type", "compressed", "length", "hash", "short", "oversize", "expiry")) {
            Fixture failing = new Fixture(golden); failing.failure = failure;
            BetaDeliveryClient failingClient = failing.client();
            BetaDeliveryClient.VerifiedCatalog fresh = failingClient.fetchCatalog();
            rejected(Exception.class, () -> failingClient.download(fresh));
            check(!failing.delivered.isEmpty() && failing.delivered.stream().allMatch(BetaDeliveryClientCheck::allZero), "failure clears all delivered bytes: " + failure);
        }
        Fixture expired = new Fixture(golden);
        BetaDeliveryClient expiredClient = expired.client();
        BetaDeliveryClient.VerifiedCatalog expiring = expiredClient.verifyManifest(expired.manifestToken);
        expired.clock.epoch += 121;
        rejected(GeneralSecurityException.class, () -> expiredClient.download(expiring));
        check(expired.requests == 0, "expired manifest makes no request");
        boundedStreaming();
        System.out.println("PASS: Celeste+ delivery client (" + checks + " checks; actual server golden signatures; offline only)");
    }

    private static List<Consumer<JsonObject>> manifestMutations() {
        return List.of(
            c -> c.addProperty("product", "celeste"), c -> c.addProperty("product", "both"), c -> c.remove("product"),
            c -> c.addProperty("sub", "7"), c -> c.addProperty("sub", "042"), c -> c.addProperty("sub", 42),
            c -> c.addProperty("device", "00000000-0000-0000-0000-000000000043"),
            c -> c.addProperty("schema", "celeste-beta-release-1"), c -> c.addProperty("aud", "celeste-client"),
            c -> c.addProperty("sub", "8"), c -> c.addProperty("device", "wrong-device"),
            c -> c.addProperty("installer_jti", "wrong-installer-jti"), c -> c.addProperty("installer_sha256", "0".repeat(64)),
            c -> c.addProperty("iat", 1800000001L), c -> c.addProperty("nbf", 1799999999L), c -> c.addProperty("exp", 1800000000L),
            c -> c.addProperty("exp", 1800000121L), c -> c.addProperty("exp", "1800000100"),
            c -> c.addProperty("release_id", "../escape"), c -> c.addProperty("release_id", "production-release"),
            c -> c.addProperty("runtime_sha256", "b".repeat(64)), c -> c.addProperty("client_version", "spaces invalid"),
            c -> c.addProperty("unexpected", "field"), c -> c.getAsJsonArray("artifacts").remove(3),
            c -> c.getAsJsonArray("artifacts").add(c.getAsJsonArray("artifacts").get(0).deepCopy()),
            c -> item(c, 0).addProperty("id", "payload-a"), c -> item(c, 0).addProperty("role", "payload"),
            c -> item(c, 0).addProperty("url", "https://auth.invalid/v1/beta/artifacts/owner-beta-1/bootstrap-a"),
            c -> item(c, 0).addProperty("url", "//foreign.invalid/leak"), c -> item(c, 0).addProperty("url", "/v1/bootstrap/production"),
            c -> item(c, 0).addProperty("url", "/v1/beta/artifacts/../production/bootstrap-a"),
            c -> item(c, 0).addProperty("filename", "payload-a.bin"), c -> item(c, 0).addProperty("sha256", "z".repeat(64)),
            c -> item(c, 0).addProperty("size", 0), c -> item(c, 0).addProperty("size", 67108865),
            c -> item(c, 0).addProperty("size", 1.25),
            c -> { for (int i = 0; i < 4; i++) item(c, i).addProperty("size", 67108864); }
        );
    }
    private static List<Consumer<JsonObject>> installerMutations() {
        return List.of(c -> c.addProperty("product", "celeste"), c -> c.addProperty("product", "both"), c -> c.remove("product"),
            c -> c.addProperty("sub", "7"), c -> c.addProperty("sub", "042"),
            c -> c.addProperty("device", "00000000-0000-0000-0000-000000000043"),
            c -> c.addProperty("sub", "8"), c -> c.addProperty("aud", "celeste-client"),
            c -> c.addProperty("exp", 1), c -> c.addProperty("nbf", 1800000006L), c -> c.addProperty("iat", 1800000006L),
            c -> c.addProperty("jti", "short"), c -> c.addProperty("bootstrap_sha256", "bad"), c -> c.addProperty("sub", 42));
    }
    private static JsonObject item(JsonObject object, int index) { return object.getAsJsonArray("artifacts").get(index).getAsJsonObject(); }
    private static JsonObject projection(JsonObject object) {
        JsonObject projection = new JsonObject();
        for (String key : List.of("schema", "release_id", "client_version", "runtime_sha256", "artifacts")) projection.add(key, object.get(key));
        return projection;
    }

    private static final class Fixture {
        final JsonObject golden;
        final MutableClock clock;
        byte[] publicKey;
        String installerToken;
        String manifestToken;
        final String device;
        final long uid;
        final Map<String, byte[]> artifacts = new LinkedHashMap<>();
        final List<byte[]> delivered = new ArrayList<>();
        String failure = "";
        int requests;
        Fixture(JsonObject golden) throws Exception {
            this.golden = golden;
            clock = new MutableClock(golden.get("now").getAsLong());
            publicKey = BetaDeliveryClient.decode(golden.get("public_key").getAsString());
            installerToken = golden.get("installer_token").getAsString();
            manifestToken = golden.get("manifest_token").getAsString();
            device = golden.get("expected_device").getAsString();
            uid = golden.get("expected_uid").getAsLong();
            for (String id : BetaDeliveryClient.IDS) artifacts.put(id, BetaDeliveryClient.decode(golden.getAsJsonObject("artifact_bytes").get(id).getAsString()));
        }
        BetaDeliveryClient client() throws GeneralSecurityException { return clientWith(installerToken, uid, device, ORIGIN); }
        BetaDeliveryClient clientWith(String token, long uid, String expected, URI origin) throws GeneralSecurityException {
            return new BetaDeliveryClient(origin, publicKey, token, expected, uid, clock, this::get);
        }
        private BetaDeliveryClient.Reply get(URI uri, Map<String, String> headers, int maximum, Duration timeout) {
            requests++;
            check(uri.getScheme().equals("https") && uri.getHost().equals("auth.invalid"), "HTTPS same origin");
            check(headers.get("Authorization").equals("Bearer " + installerToken), "original installer token only");
            check(timeout.toSeconds() == 25, "bounded request deadline");
            if (uri.getPath().equals("/v1/beta/manifest")) {
                check(!headers.containsKey("X-Celeste-Beta-Manifest"), "catalog request has no stale manifest header");
                JsonObject wrapper = new JsonObject(); wrapper.addProperty("manifest", manifestToken);
                return new BetaDeliveryClient.Reply(uri, 200, Map.of("Content-Type", List.of("application/json")), wrapper.toString().getBytes(StandardCharsets.UTF_8));
            }
            String id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            check(uri.getPath().equals("/v1/beta/artifacts/" + golden.getAsJsonObject("manifest_claims").get("release_id").getAsString() + "/" + id), "fixed artifact route");
            check(headers.get("X-Celeste-Beta-Manifest").equals(manifestToken), "exact signed manifest header");
            byte[] content = artifacts.get(id).clone();
            check(maximum == content.length, "transport bound equals signed size");
            URI finalUri = uri; int status = 200;
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("Content-Type", List.of("application/octet-stream"));
            if (id.equals("bootstrap-b")) switch (failure) {
                case "foreign" -> finalUri = URI.create("https://foreign.invalid/leak");
                case "redirect" -> status = 302;
                case "denied" -> status = 401;
                case "type" -> responseHeaders.put("Content-Type", List.of("text/html"));
                case "compressed" -> responseHeaders.put("Content-Encoding", List.of("gzip"));
                case "length" -> responseHeaders.put("Content-Length", List.of("999"));
                case "hash" -> content[0] ^= 1;
                case "short" -> content = Arrays.copyOf(content, content.length - 1);
                case "oversize" -> content = Arrays.copyOf(content, content.length + 1);
                case "expiry" -> clock.epoch += 121;
                default -> { }
            }
            delivered.add(content);
            return new BetaDeliveryClient.Reply(finalUri, status, responseHeaders, content);
        }
    }

    private static final class Synthetic {
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final Fixture fixture;
        final JsonObject installer;
        final JsonObject manifest;
        Synthetic(JsonObject golden) throws Exception {
            fixture = new Fixture(golden);
            byte[] encoded = keys.getPublic().getEncoded(); fixture.publicKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            installer = golden.getAsJsonObject("installer_claims").deepCopy();
            fixture.installerToken = sign(installer);
            manifest = golden.getAsJsonObject("manifest_claims").deepCopy();
            manifest.addProperty("installer_sha256", BetaDeliveryClient.sha256(fixture.installerToken.getBytes(StandardCharsets.US_ASCII)));
            fixture.manifestToken = sign(manifest);
        }
        String sign(JsonObject claims) throws Exception { return signRaw(claims.toString()); }
        String signRaw(String claims) throws Exception {
            String header = encode("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8));
            String payload = encode(claims.getBytes(StandardCharsets.UTF_8));
            Signature signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate());
            signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + encode(signer.sign());
        }
    }
    private static final class MutableClock extends Clock {
        long epoch;
        MutableClock(long epoch) { this.epoch = epoch; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(epoch); }
    }
    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;
        @Override public void request(long count) { check(count == 1, "stream requests bounded demand"); }
        @Override public void cancel() { cancelled = true; }
    }
    private static void boundedStreaming() throws Exception {
        HttpsTransport.BoundedBody body = new HttpsTransport.BoundedBody(4); Subscription subscription = new Subscription();
        body.onSubscribe(subscription); body.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}), ByteBuffer.wrap(new byte[]{3, 4}))); body.onComplete();
        check(Arrays.equals(body.getBody().toCompletableFuture().join(), new byte[]{1, 2, 3, 4}), "bounded streaming assembles chunks");
        HttpsTransport.BoundedBody oversized = new HttpsTransport.BoundedBody(2); Subscription overflow = new Subscription();
        oversized.onSubscribe(overflow); oversized.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        rejected(CompletionException.class, () -> oversized.getBody().toCompletableFuture().join());
        check(overflow.cancelled, "oversized response cancels network subscription");
        HttpsTransport.BoundedBody failed = new HttpsTransport.BoundedBody(4); Subscription cancelled = new Subscription();
        failed.onSubscribe(cancelled); failed.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2}))); failed.fail(new IOException("offline cancellation"));
        rejected(CompletionException.class, () -> failed.getBody().toCompletableFuture().join());
        check(cancelled.cancelled, "cancellation stops partial body");
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] read(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes; }
    private static boolean allZero(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void rejected(Class<? extends Exception> expected, Throwing action) throws Exception {
        try { action.run(); } catch (Exception error) { if (expected.isInstance(error)) { checks++; return; } throw error; }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
