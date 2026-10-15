package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import dev.c4lam.celeste.auth.DpopProofSigner;
import java.io.IOException;
import java.net.SocketException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        envelopeVector();
        Fixture serverGolden = new Fixture(golden);
        BetaDeliveryClient.VerifiedCatalog serverCatalog = serverGolden.client().verifyManifest(serverGolden.manifestToken, serverGolden.nonce);
        check(serverCatalog.catalogSha256().equals(golden.get("catalog_sha256").getAsString()), "actual signed server catalog");
        rejected(GeneralSecurityException.class, () -> serverGolden.client().fetchCatalog());
        Fixture fixture = new Synthetic(golden).fixture;
        check(fixture.uid == 42, "arbitrary UID42 accepted without Owner UID exemption");
        check(golden.getAsJsonObject("installer_claims").get("product").getAsString().equals("celeste_plus"), "server installer is scoped to Celeste+");
        check(golden.getAsJsonObject("manifest_claims").get("product").getAsString().equals("celeste_plus"), "server manifest is scoped to Celeste+");
        BetaDeliveryClient client = fixture.client();
        BetaDeliveryClient.VerifiedCatalog catalog = client.verifyManifest(fixture.manifestToken, fixture.nonce);
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
        check(fixture.proofIds.size() == 5, "catalog and all four artifacts have independent fresh signed proofs");
        catalog = client.fetchCatalog();
        check(fixture.proofIds.size() == 6, "retry on the same route does not reuse a proof");
        Synthetic retryServer = new Synthetic(golden);
        Fixture retryFixture = retryServer.fixture;
        retryFixture.transientManifestFailures = 1;
        retryFixture.transientArtifactFailures = 1;
        BetaDeliveryClient retryClient = retryFixture.client();
        BetaDeliveryClient.VerifiedCatalog retryCatalog = retryClient.fetchCatalog();
        try (BetaDeliveryClient.MemoryRelease retried = retryClient.download(retryCatalog)) {
            check(Arrays.equals(read(retried.artifact("payload-b")), retryFixture.artifacts.get("payload-b")),
                "transient connection reset retries the artifact transfer");
        }
        check(retryFixture.requestNonces.size() == 7, "manifest and artifact retries use fresh request and envelope nonces");
        check(retryFixture.proofIds.size() == 7, "manifest and artifact retries use fresh DPoP proofs");
        check(HttpsTransport.failureCategory(new SocketException("Connection reset; token=must-not-appear"))
            .equals("connection reset"), "transport diagnostics redact exception text");
        check(HttpsTransport.failureCategory(new IOException("wrapped", new IOException("Beta response exceeded its byte limit")))
            .equals("response exceeded its allowed size"), "bounded-body failures have a safe diagnostic");
        fixture.hardware = "7".repeat(64);
        try (var changedHardware = client.download(catalog)) {
            check(changedHardware.artifact("bootstrap-a").remaining() > 0, "hardware provider sampled on each artifact request");
        }
        int sent = fixture.requests;
        for (String invalidHardware : new String[] {"", "A".repeat(64), "short", null}) {
            fixture.hardware = invalidHardware;
            final var ownedCatalog = catalog;
            rejected(GeneralSecurityException.class, () -> client.download(ownedCatalog));
            check(fixture.requests == sent, "missing or malformed hardware never sends artifact authorization");
        }
        fixture.hardware = "6".repeat(64);
        KeyPair otherInstallation = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] otherPublic = otherInstallation.getPublic().getEncoded();
        DpopProofSigner wrongSigner = new DpopProofSigner(otherInstallation.getPrivate(),
            Arrays.copyOfRange(otherPublic, otherPublic.length - 32, otherPublic.length), fixture.clock);
        rejected(GeneralSecurityException.class, () -> new BetaDeliveryClient(ORIGIN, fixture.publicKey,
            fixture.installerToken, fixture.device, fixture.uid, wrongSigner, () -> "6".repeat(64), fixture.clock, fixture::get));
        rejected(GeneralSecurityException.class, () -> new BetaDeliveryClient(ORIGIN, fixture.publicKey,
            fixture.installerToken, fixture.device, fixture.uid, null, () -> "6".repeat(64), fixture.clock, fixture::get));
        Fixture closedFixture = new Fixture(golden);
        BetaDeliveryClient closedClient = closedFixture.client();
        BetaDeliveryClient.MemoryRelease closed = closedClient.download(closedClient.verifyManifest(closedFixture.manifestToken, closedFixture.nonce));
        ByteBuffer retained = closed.artifact("payload-a");
        closed.close(); closed.close();
        check(allZero(read(retained)), "close zeroes retained views");
        rejected(IllegalStateException.class, () -> closed.artifact("payload-a"));
        BetaDeliveryClient other = fixture.client();
        BetaDeliveryClient.VerifiedCatalog owned = client.fetchCatalog();
        rejected(GeneralSecurityException.class, () -> other.download(owned));

        Synthetic synthetic = new Synthetic(golden);
        BetaDeliveryClient verifier = synthetic.fixture.client();
        int mutationIndex = 0;
        for (Consumer<JsonObject> mutation : manifestMutations()) {
            JsonObject changed = synthetic.manifest.deepCopy(); mutation.accept(changed);
            // Recompute the catalog digest so structural guards, not only digest mismatch, must reject.
            changed.addProperty("catalog_sha256", BetaDeliveryClient.sha256(BetaDeliveryClient.canonical(projection(changed)).getBytes(StandardCharsets.UTF_8)));
            try { rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(synthetic.sign(changed), synthetic.fixture.nonce)); }
            catch (AssertionError error) { throw new AssertionError("Manifest mutation " + mutationIndex + " was accepted", error); }
            mutationIndex++;
        }
        JsonObject badDigest = synthetic.manifest.deepCopy(); badDigest.addProperty("catalog_sha256", "0".repeat(64));
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(synthetic.sign(badDigest), synthetic.fixture.nonce));
        for (String bad : List.of("invalid", synthetic.fixture.manifestToken + "=", synthetic.fixture.manifestToken.substring(1)))
            rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(bad, synthetic.fixture.nonce));
        String duplicate = BetaDeliveryClient.canonical(synthetic.manifest);
        duplicate = "{\"schema\":\"duplicate\"," + duplicate.substring(1);
        String signedDuplicate = synthetic.signRaw(duplicate);
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(signedDuplicate, synthetic.fixture.nonce));
        Synthetic foreign = new Synthetic(golden);
        rejected(GeneralSecurityException.class, () -> verifier.verifyManifest(foreign.fixture.manifestToken, synthetic.fixture.nonce));
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
            Fixture failing = new Synthetic(golden).fixture; failing.failure = failure;
            BetaDeliveryClient failingClient = failing.client();
            BetaDeliveryClient.VerifiedCatalog fresh = failingClient.fetchCatalog();
            int requestsBeforeDownload = failing.requests;
            rejected(Exception.class, () -> failingClient.download(fresh));
            check(failing.requests == requestsBeforeDownload + 2,
                "authorization, response, and integrity failures are not retried: " + failure);
            check(!failing.delivered.isEmpty() && failing.delivered.stream().allMatch(BetaDeliveryClientCheck::allZero), "failure clears all delivered bytes: " + failure);
        }
        Fixture expired = new Fixture(golden);
        BetaDeliveryClient expiredClient = expired.client();
        BetaDeliveryClient.VerifiedCatalog expiring = expiredClient.verifyManifest(expired.manifestToken, expired.nonce);
        expired.clock.epoch += 121;
        rejected(GeneralSecurityException.class, () -> expiredClient.download(expiring));
        check(expired.requests == 0, "expired manifest makes no request");
        freshBoundaries(golden);
        boundedStreaming();
        System.out.println("PASS: Celeste+ delivery client (" + checks + " checks; actual server golden signatures; offline only)");
    }

    private static List<Consumer<JsonObject>> manifestMutations() {
        return List.of(
            c -> c.remove("request_nonce"), c -> c.addProperty("request_nonce", "A".repeat(32)),
            c -> c.addProperty("request_nonce", 42), c -> c.addProperty("request_nonce", "short"),
            c -> c.remove("cnf"), c -> c.addProperty("cnf", "invalid"), c -> c.add("cnf", new JsonObject()),
            c -> c.getAsJsonObject("cnf").addProperty("jkt", "A".repeat(43)),
            c -> c.getAsJsonObject("cnf").addProperty("jkt", 42),
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
        return List.of(c -> c.remove("cnf"), c -> c.addProperty("cnf", "invalid"), c -> c.add("cnf", new JsonObject()),
            c -> c.getAsJsonObject("cnf").addProperty("jkt", "A".repeat(43)),
            c -> c.getAsJsonObject("cnf").addProperty("jkt", 42),
            c -> c.addProperty("product", "celeste"), c -> c.addProperty("product", "both"), c -> c.remove("product"),
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
        final String nonce;
        Synthetic server;
        final String device;
        final long uid;
        final DpopProofSigner proofSigner;
        final byte[] installationPublic;
        final Set<String> proofIds = new HashSet<>();
        final Set<String> requestNonces = new HashSet<>();
        final Map<String, byte[]> artifacts = new LinkedHashMap<>();
        final List<byte[]> delivered = new ArrayList<>();
        String hardware = "6".repeat(64);
        String failure = "";
        int transientManifestFailures;
        int transientArtifactFailures;
        int requests;
        Fixture(JsonObject golden) throws Exception {
            this.golden = golden;
            clock = new MutableClock(golden.get("now").getAsLong());
            publicKey = BetaDeliveryClient.decode(golden.get("public_key").getAsString());
            installerToken = golden.get("installer_token").getAsString();
            manifestToken = golden.get("manifest_token").getAsString();
            nonce = golden.get("expected_request_nonce").getAsString();
            device = golden.get("expected_device").getAsString();
            uid = golden.get("expected_uid").getAsLong();
            installationPublic = BetaDeliveryClient.decode(golden.get("installation_public_key").getAsString());
            byte[] seed = BetaDeliveryClient.decode(golden.get("installation_test_seed").getAsString());
            try {
                proofSigner = new DpopProofSigner(KeyFactory.getInstance("Ed25519").generatePrivate(
                    new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed)), installationPublic, clock);
            } finally { Arrays.fill(seed, (byte) 0); }
            String canonicalJwk = "{\"crv\":\"Ed25519\",\"kty\":\"OKP\",\"x\":\"" + encode(installationPublic) + "\"}";
            check(proofSigner.thumbprint().equals(encode(MessageDigest.getInstance("SHA-256")
                .digest(canonicalJwk.getBytes(StandardCharsets.US_ASCII)))), "independent RFC7638 thumbprint");
            for (String id : BetaDeliveryClient.IDS) artifacts.put(id, BetaDeliveryClient.decode(golden.getAsJsonObject("artifact_bytes").get(id).getAsString()));
        }
        BetaDeliveryClient client() throws GeneralSecurityException { return clientWith(installerToken, uid, device, ORIGIN); }
        BetaDeliveryClient clientWith(String token, long uid, String expected, URI origin) throws GeneralSecurityException {
            return new BetaDeliveryClient(origin, publicKey, token, expected, uid, proofSigner, () -> hardware, clock, this::get);
        }
        private BetaDeliveryClient.Reply get(URI uri, Map<String, String> headers, int maximum, Duration timeout) throws IOException {
            requests++;
            check(uri.getScheme().equals("https") && uri.getHost().equals("auth.invalid"), "HTTPS same origin");
            check(headers.get("Authorization").equals("DPoP " + installerToken), "original installer token with DPoP scheme only");
            try { verifyProof(uri, headers); }
            catch (GeneralSecurityException failure) { throw new IOException("Fixture rejected invalid request proof", failure); }
            check(timeout.toSeconds() == 25, "bounded request deadline");
            if (uri.getPath().equals("/v1/beta/manifest")) {
                check(!headers.containsKey("X-Celeste-Beta-Manifest"), "catalog request has no stale manifest header");
                String requestNonce = headers.get("X-Celeste-Request-Nonce");
                check(requestNonce != null && requestNonce.matches("[A-Za-z0-9_-]{32}") && requestNonces.add(requestNonce),
                    "fresh canonical 24-byte request nonce");
                if (transientManifestFailures > 0) {
                    transientManifestFailures--;
                    throw new SocketException("Connection reset");
                }
                if (failure.equals("offline")) throw new IOException("fixture offline");
                if (failure.equals("manifest-denied")) return new BetaDeliveryClient.Reply(uri, 403,
                    Map.of("Content-Type", List.of("application/json")), new byte[0]);
                if (server != null && !failure.equals("replay")) {
                    JsonObject fresh = server.manifest.deepCopy();
                    fresh.addProperty("request_nonce", requestNonce);
                    try {
                        if (failure.equals("catalog-changed")) {
                            fresh.addProperty("client_version", "changed");
                            fresh.addProperty("catalog_sha256", BetaDeliveryClient.sha256(BetaDeliveryClient.canonical(projection(fresh)).getBytes(StandardCharsets.UTF_8)));
                        }
                        manifestToken = server.sign(fresh);
                    }
                    catch (Exception signing) { throw new IOException("fixture response signing failed", signing); }
                }
                JsonObject wrapper = new JsonObject(); wrapper.addProperty("manifest", manifestToken);
                return new BetaDeliveryClient.Reply(uri, 200, Map.of("Content-Type", List.of("application/json")), wrapper.toString().getBytes(StandardCharsets.UTF_8));
            }
            String id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            check(uri.getPath().equals("/v1/beta/artifacts/" + golden.getAsJsonObject("manifest_claims").get("release_id").getAsString() + "/" + id), "fixed artifact route");
            check(headers.get("X-Celeste-Beta-Manifest").equals(manifestToken), "exact signed manifest header");
            if (transientArtifactFailures > 0) {
                transientArtifactFailures--;
                throw new SocketException("Connection reset");
            }
            byte[] content = artifacts.get(id).clone();
            check(maximum == content.length + ArtifactEnvelope.OVERHEAD, "transport bound equals signed plaintext plus fixed AEAD overhead");
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
            try { content = seal(content, uri.getRawPath(), installerToken, headers.get("X-Celeste-Request-Nonce"), headers.get("X-Celeste-Delivery-Key")); }
            catch (GeneralSecurityException error) { throw new IOException(error); }
            if (id.equals("bootstrap-b") && failure.equals("aead-tag")) content[content.length - 1] ^= 1;
            if (id.equals("bootstrap-b") && failure.equals("aead-magic")) content[0] ^= 1;
            delivered.add(content);
            return new BetaDeliveryClient.Reply(finalUri, status, responseHeaders, content);
        }
        private void verifyProof(URI uri, Map<String, String> headers) throws GeneralSecurityException {
            String proof = headers.get("DPoP");
            check(proof != null, "DPoP proof header required on every request");
            String[] parts = proof.split("\\.", -1);
            check(parts.length == 3, "compact proof JWT");
            JsonObject header = BetaDeliveryClient.object(BetaDeliveryClient.decode(parts[0]));
            check(header.keySet().equals(Set.of("typ", "alg", "jwk"))
                && header.get("typ").getAsString().equals("dpop+jwt")
                && header.get("alg").getAsString().equals("EdDSA"), "RFC9449 public proof header");
            JsonObject jwk = header.getAsJsonObject("jwk");
            check(jwk.keySet().equals(Set.of("crv", "kty", "x")) && jwk.get("crv").getAsString().equals("Ed25519")
                && jwk.get("kty").getAsString().equals("OKP")
                && jwk.get("x").getAsString().equals(encode(installationPublic)), "proof embeds only the installation public JWK");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(BetaDeliveryClient.publicKey(installationPublic));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            check(verifier.verify(BetaDeliveryClient.decode(parts[2])), "real Ed25519 request proof signature");
            JsonObject claims = BetaDeliveryClient.object(BetaDeliveryClient.decode(parts[1]));
            boolean artifact = uri.getPath().startsWith("/v1/beta/artifacts/");
            check(claims.keySet().equals(artifact ? Set.of("jti", "htm", "htu", "iat", "ath", "cek", "cnonce", "hwid") : Set.of("jti", "htm", "htu", "iat", "ath")), "exact resource request proof claims");
            if (artifact) {
                check(claims.get("hwid").getAsString().equals(headers.get("X-Device-Id"))
                    && hardware.equals(headers.get("X-Device-Id")), "current hardware signed in the same recipient proof");
                check(claims.get("cek").getAsString().equals(headers.get("X-Celeste-Delivery-Key")) && claims.get("cnonce").getAsString().equals(headers.get("X-Celeste-Request-Nonce")), "encrypted artifact recipient and nonce signed by installation key");
                check(requestNonces.add(headers.get("X-Celeste-Request-Nonce")), "fresh artifact encryption nonce");
            }
            check(claims.get("htm").getAsString().equals("GET") && claims.get("htu").getAsString().equals(uri.toString()),
                "proof is bound to this method and individual route");
            check(claims.get("iat").getAsLong() == clock.epoch, "proof timestamp uses current clock");
            check(claims.get("ath").getAsString().equals(encode(MessageDigest.getInstance("SHA-256")
                .digest(installerToken.getBytes(StandardCharsets.US_ASCII)))), "proof ath binds exact installer token, not manifest");
            String jti = claims.get("jti").getAsString();
            check(BetaDeliveryClient.decode(jti).length >= 16 && proofIds.add(jti), "unpredictable nonreused proof identifier");
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
            fixture.server = this;
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
    private static void freshBoundaries(JsonObject golden) throws Exception {
        Synthetic synthetic = new Synthetic(golden);
        Fixture fixture = synthetic.fixture;
        BetaDeliveryClient client = fixture.client();
        BetaDeliveryClient.VerifiedCatalog catalog = client.fetchCatalog();
        client.revalidate(catalog);
        client.revalidate(catalog);
        check(fixture.requestNonces.size() == 3, "download, B and payload boundaries require separate fresh server responses");
        check(fixture.delivered.isEmpty(), "revalidation does not redownload retained artifacts");
        for (String failure : List.of("replay", "offline", "manifest-denied", "catalog-changed")) {
            fixture.failure = failure;
            rejected(Exception.class, () -> client.revalidate(catalog));
        }
        for (String invalid : new String[] { null, "", "A".repeat(31), "A".repeat(33), fixture.nonce + "=", "!".repeat(32) })
            rejected(GeneralSecurityException.class, () -> client.verifyManifest(synthetic.sign(synthetic.manifest), invalid));
        fixture.failure = "";
        client.revalidate(catalog);
        BetaDeliveryClient other = fixture.client();
        int requests = fixture.requests;
        rejected(GeneralSecurityException.class, () -> other.revalidate(catalog));
        check(fixture.requests == requests, "foreign catalog cannot trigger revalidation");
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
    private static void envelopeVector() throws Exception {
        JsonObject vector = com.google.gson.JsonParser.parseString(Files.readString(Path.of("auth-service/tests/fixtures/artifact-envelope-v1.json"))).getAsJsonObject();
        byte[] privateKey = Base64.getUrlDecoder().decode(vector.get("client_private_key").getAsString());
        byte[] publicKey = Base64.getUrlDecoder().decode(vector.get("client_public_key").getAsString());
        byte[] prefix = java.util.HexFormat.of().parseHex("302a300506032b656e032100");
        byte[] encoded = Arrays.copyOf(prefix,prefix.length+32);System.arraycopy(publicKey,0,encoded,prefix.length,32);
        KeyPair pair = new KeyPair(KeyFactory.getInstance("X25519").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded)),
            KeyFactory.getInstance("X25519").generatePrivate(new java.security.spec.XECPrivateKeySpec(NamedParameterSpec.X25519,privateKey)));
        byte[] envelope = Base64.getUrlDecoder().decode(vector.get("envelope").getAsString()), plain = Base64.getUrlDecoder().decode(vector.get("plaintext").getAsString());
        String path=vector.get("request_path").getAsString(), token=vector.get("installer_token").getAsString(), nonce=vector.get("request_nonce").getAsString();
        check(Arrays.equals(plain,new ArtifactEnvelope(pair,nonce).open(envelope,path,token,plain.length)),"actual Python backend AEAD ciphertext decrypted by Java");
        for(int i=0;i<envelope.length;i++) {byte[] bad=envelope.clone();bad[i]^=1;rejected(GeneralSecurityException.class,()->new ArtifactEnvelope(pair,nonce).open(bad,path,token,plain.length));}
        rejected(GeneralSecurityException.class,()->new ArtifactEnvelope(pair,nonce).open(envelope,path+"-other",token,plain.length));
        rejected(GeneralSecurityException.class,()->new ArtifactEnvelope(pair,nonce).open(envelope,path,token+"x",plain.length));
        rejected(GeneralSecurityException.class,()->new ArtifactEnvelope(pair,"Z".repeat(32)).open(envelope,path,token,plain.length));
        rejected(GeneralSecurityException.class,()->new ArtifactEnvelope(pair,nonce).open(plain,path,token,plain.length));
    }

    static byte[] seal(byte[] plain, String path, String token, String nonce, String publicKey) throws GeneralSecurityException {
        var server = KeyPairGenerator.getInstance("X25519").generateKeyPair();
        byte[] prefix = java.util.HexFormat.of().parseHex("302a300506032b656e032100");
        byte[] client = Base64.getUrlDecoder().decode(publicKey);
        byte[] encoded = Arrays.copyOf(prefix, prefix.length + client.length); System.arraycopy(client, 0, encoded, prefix.length, client.length);
        var ka = javax.crypto.KeyAgreement.getInstance("X25519"); ka.init(server.getPrivate());
        ka.doPhase(KeyFactory.getInstance("X25519").generatePublic(new java.security.spec.X509EncodedKeySpec(encoded)), true);
        byte[] secret = ka.generateSecret();
        var hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)), "HmacSHA256"));
        byte[] prk = hmac.doFinal(secret);
        byte[] info = ("celeste-artifact-v1\n" + path + "\n" + nonce + "\n" + publicKey).getBytes(StandardCharsets.UTF_8);
        hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256")); hmac.update(info); byte[] key = hmac.doFinal(new byte[]{1});
        byte[] iv = new byte[12]; new java.security.SecureRandom().nextBytes(iv);
        var aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); aes.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key,"AES"), new javax.crypto.spec.GCMParameterSpec(128,iv)); aes.updateAAD(info);
        byte[] cipher = aes.doFinal(plain), pub = server.getPublic().getEncoded();
        byte[] output = ByteBuffer.allocate(50 + cipher.length).put("CSTAE1".getBytes(StandardCharsets.US_ASCII)).put(pub,pub.length-32,32).put(iv).put(cipher).array();
        Arrays.fill(secret,(byte)0);Arrays.fill(prk,(byte)0);Arrays.fill(key,(byte)0);
        return output;
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
