package dev.c4lam.celeste.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Real client protocol and cryptography; transport never opens a socket. */
public final class BetaClientProtocolCheck {
    private static final Gson JSON = new Gson();
    private static final String DIGEST = "a".repeat(64);
    private static final String CATALOG = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);
    private static final String DEVICE = "10000000-0000-0000-0000-000000000007";
    private static final GameIdentity IDENTITY = new GameIdentity("00000000-0000-0000-0000-000000000007", "FixtureOwner");
    private static final HardwareFingerprint HARDWARE = new HardwareFingerprint(DIGEST, DIGEST, DIGEST, List.of(DIGEST));
    private static final String CORE_HARDWARE = AuthServiceClient.coreHardwareDigest(HARDWARE);
    private static final String HWID = "d".repeat(64), SESSION = "S".repeat(32);
    private static final long PID = 12345;
    private static final AuthServiceClient.SessionBinding BINDING = new AuthServiceClient.SessionBinding(CORE_HARDWARE, HWID, PID, SESSION);
    private static int checks;

    private BetaClientProtocolCheck() { }

    public static void main(String[] args) throws Exception {
        Fixture normal = new Fixture(false);
        var normalAuth = normal.client.authenticate(normal.credential, HARDWARE);
        check(normalAuth.payload() != null && normalAuth.loader() != null, "production download contract retained");
        normal.client.checkSession(normal.credential, DIGEST, false);
        check(normal.transport.proofs == 2, "production dual proofs verified for auth and heartbeat");
        protectedRequests(normal, normalAuth.grant());

        Fixture beta = new Fixture(true);
        var betaAuth = beta.client.authenticate(beta.credential, HARDWARE);
        check(betaAuth.payload() == null && betaAuth.loader() == null, "beta grants confer no production download contract");
        beta.client.checkSession(beta.credential, DIGEST, false);
        check(beta.transport.proofs == 2, "beta dual proofs verified for auth and heartbeat");
        check(beta.transport.validations==1 && normal.transport.validations==0,"new Plus grant validated exactly once; Standard unchanged");
        for(String mutation:List.of("status","expiry")) {Fixture bad=new Fixture(true);bad.transport.validateMutation=mutation;securityRejected(()->bad.client.authenticate(bad.credential,HARDWARE),"startup rejected invalid validate response");}
        for(String mutation:List.of("hardware","hwid","pid","session")) {Fixture bad=new Fixture(true);bad.transport.sessionMutation=mutation;securityRejected(()->bad.client.checkSession(bad.credential,DIGEST,false),"signed runtime identity must match "+mutation);}
        for(String code:List.of("device_mismatch","subscription_inactive","token_revoked","token_rotated","concurrent_session_rejected","reactivation_required")) {
            Fixture bad=new Fixture(true);bad.transport.failureStatus=401;bad.transport.errorCode=code;
            try{bad.client.authenticate(bad.credential,HARDWARE);throw new AssertionError("accepted typed error");}catch(AuthServiceClient.AuthRejectedException e){
                check(e.code().equals(code)&&e.requestId().equals("fixture-request")&&e.retryAfterSeconds()==123,"typed error metadata retained safely");
            }
        }
        for(String mutation:List.of("protocol","hwid_sha256","hardware")) {
            Fixture bad=new Fixture(true);bad.transport.challengeMutation=mutation;
            securityRejected(()->bad.client.authenticate(bad.credential,HARDWARE),"challenge binding rejected before signing: "+mutation);
            check(bad.transport.proofs==0,"no signed complete request for mismatched challenge");
        }
        messageVectors(normal.client, beta.client);
        sessionFreshnessAndExpiry();

        for (String mutation : List.of("product", "missing-product", "object-product", "catalog", "release", "digest", "version", "uid", "device", "signature", "artifact", "stage", "missing-cnf", "wrong-cnf")) {
            Fixture fixture = new Fixture(true);
            fixture.transport.authMutation = mutation;
            rejected(() -> fixture.client.authenticate(fixture.credential, HARDWARE), "beta auth rejects " + mutation);
        }
        for (String mutation : List.of("product", "missing-product", "object-product", "catalog", "release", "digest", "version", "uid", "device", "signature", "nonce", "missing-cnf", "wrong-cnf")) {
            Fixture fixture = new Fixture(true);
            fixture.transport.sessionMutation = mutation;
            rejected(() -> fixture.client.checkSession(fixture.credential, DIGEST, false), "beta heartbeat rejects " + mutation);
        }
        Fixture unexpectedBeta = new Fixture(false);
        unexpectedBeta.transport.respondBeta = true;
        rejected(() -> unexpectedBeta.client.authenticate(unexpectedBeta.credential, HARDWARE), "normal bootstrap rejects beta runtime response");
        Fixture unexpectedNormal = new Fixture(true);
        unexpectedNormal.transport.respondBeta = false;
        rejected(() -> unexpectedNormal.client.authenticate(unexpectedNormal.credential, HARDWARE), "beta runtime rejects normal loader response");
        Fixture missingContext = new Fixture(true);
        missingContext.transport.authMutation = "missing-catalog";
        rejected(() -> missingContext.client.authenticate(missingContext.credential, HARDWARE), "missing signed catalog rejected");
        for (String mutation : List.of("missing-catalog", "malformed-catalog", "object-catalog",
            "missing-version", "object-version", "missing-release", "object-release")) {
            Fixture fixture = new Fixture(true);
            fixture.transport.authMutation = mutation;
            securityRejected(() -> fixture.client.authenticate(fixture.credential, HARDWARE),
                "malformed beta auth context is a security failure: " + mutation);
            fixture.transport.sessionMutation = mutation;
            securityRejected(() -> fixture.client.checkSession(fixture.credential, DIGEST, false),
                "malformed beta heartbeat context locks immediately: " + mutation);
        }
        Fixture staleBeta = new Fixture(true);
        staleBeta.transport.failureStatus = 409;
        statusRejected(() -> staleBeta.client.authenticate(staleBeta.credential, HARDWARE), 409,
            "Refresh the beta release before trying again");
        Fixture normalConflict = new Fixture(false);
        normalConflict.transport.failureStatus = 409;
        statusRejected(() -> normalConflict.client.authenticate(normalConflict.credential, HARDWARE), 409,
            "This license needs its device binding reset");
        Fixture mismatchReport = new Fixture(true);
        rejected(() -> mismatchReport.client.checkSession(mismatchReport.credential, OTHER, true), "tamper report cannot authorize");
        check(mismatchReport.transport.sawTamper, "tamper report keeps dual-signed beta context");
        rejected(() -> new AuthServiceClient(beta.config, IDENTITY, "not-a-hash", beta.transport), "malformed host catalog rejected");
        System.out.println("PASS: beta client protocol (" + checks + " checks; actual Ed25519 proofs, no network)");
    }

    private static void protectedRequests(Fixture fixture, String grant) throws Exception {
        for (String path : List.of("/v1/loader/test", "/v1/blob/a", "/v1/blob/b")) {
            check(Arrays.equals(fixture.client.download(path, grant), new byte[]{1, 2, 3}), "protected artifact response preserved");
        }
        fixture.client.cloudRequest("GET", "/v1/configs?sort=recent", null, null, grant);
        fixture.client.cloudRequest("POST", "/v1/configs", "application/json", "{}".getBytes(StandardCharsets.UTF_8), grant);
        JsonObject ticket = fixture.transport.claims("celeste-installer", "");
        ticket.addProperty("payload_release", fixture.config.releaseId());
        ticket.addProperty("payload_sha256", DIGEST);
        ticket.addProperty("bootstrap", "fixture-bootstrap");
        ticket.addProperty("bootstrap_sha256", DIGEST);
        fixture.client.consumeInstallerTicket(fixture.signed(ticket, false), fixture.credential);
        check(fixture.transport.protectedRequests == 6, "artifact, cloud and installer requests all carry token-bound proofs");
        Fixture fresh = new Fixture(false);
        try {
            fresh.client.download("/v1/blob/a", grant);
            throw new AssertionError("uninitialized signing context accepted");
        } catch (IOException expected) {
            check(fresh.transport.protectedRequests == 0, "missing installation signer rejected before transport");
        }
    }

    private static void sessionFreshnessAndExpiry() throws Exception {
        for (boolean beta : List.of(false, true)) {
            Fixture fixture = new Fixture(beta);
            String first = fixture.client.checkSession(fixture.credential, DIGEST, false);
            long remaining = fixture.client.sessionValidityNanos(first);
            check(remaining > 0 && remaining <= java.util.concurrent.TimeUnit.SECONDS.toNanos(60),
                "lease is bounded by verified server expiration");
            fixture.transport.replayedSession = first;
            securityRejected(() -> fixture.client.checkSession(fixture.credential, DIGEST, false),
                "captured signed session cannot answer a fresh request nonce");

            JsonObject claims = fixture.transport.claims("celeste-session", "");
            claims.addProperty("exp", Instant.now().getEpochSecond() + 3600);
            check(fixture.client.sessionValidityNanos(fixture.signed(claims, false))
                    == java.util.concurrent.TimeUnit.SECONDS.toNanos(90),
                "local lease never exceeds the maximum server-session lifetime");
            claims.addProperty("exp", Instant.now().getEpochSecond() - 1);
            securityRejected(() -> fixture.client.sessionValidityNanos(fixture.signed(claims, false)),
                "expired signed session cannot produce a lease");
            claims.addProperty("exp", Instant.now().getEpochSecond() + 60);
            securityRejected(() -> fixture.client.sessionValidityNanos(fixture.signed(claims, true)),
                "forged signature cannot produce a lease");
            claims.addProperty("aud", "celeste-client");
            securityRejected(() -> fixture.client.sessionValidityNanos(fixture.signed(claims, false)),
                "download grant cannot be used as a runtime lease");
        }
    }

    private static void messageVectors(AuthServiceClient normal, AuthServiceClient beta) throws Exception {
        Method method = AuthServiceClient.class.getDeclaredMethod("sessionMessage", String.class, long.class,
            String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        String expected = "celeste-session-v1\n" + DEVICE + "\n1735689600\nfixture-nonce\n"
            + DIGEST + "\n" + DIGEST + "\nheartbeat\n" + IDENTITY.uuid() + "\n" + IDENTITY.username();
        byte[] plain = (byte[]) method.invoke(normal, DEVICE, 1735689600L, "fixture-nonce", DIGEST, DIGEST, "heartbeat");
        check(Arrays.equals(plain, expected.getBytes(StandardCharsets.UTF_8)), "production transcript exact bytes unchanged");
        byte[] scoped = (byte[]) method.invoke(beta, DEVICE, 1735689600L, "fixture-nonce", DIGEST, DIGEST, "heartbeat");
        check(Arrays.equals(scoped, (expected + "\nowner-beta-test\n1.0-test\n" + CATALOG + BINDING.signedSuffix()).getBytes(StandardCharsets.UTF_8)),
            "beta transcript exact release/version/catalog suffix, no trailing newline");
    }

    private static final class Fixture {
        final KeyPair server = Crypto.generateEd25519();
        final KeyPair install = Crypto.generateEd25519();
        final KeyPair token = Crypto.generateEd25519();
        final DpopProofSigner signer;
        final AuthConfig config;
        final InstallCredential credential;
        final FakeHttp transport;
        final AuthServiceClient client;

        Fixture(boolean beta) throws Exception {
            config = new AuthConfig(URI.create("https://auth.invalid"), Crypto.rawPublic(server.getPublic()),
                "owner-beta-test", DIGEST, "1.0-test");
            signer = new DpopProofSigner(install.getPrivate(), Crypto.rawPublic(install.getPublic()));
            JsonObject certificate = new JsonObject();
            certificate.addProperty("aud", "celeste-installation");
            certificate.addProperty("sub", "42");
            certificate.addProperty("device", DEVICE);
            certificate.addProperty("install_key", Crypto.b64(Crypto.rawPublic(install.getPublic())));
            certificate.addProperty("nbf", Instant.now().getEpochSecond() - 1);
            certificate.addProperty("exp", Instant.now().getEpochSecond() + 600);
            byte[] tokenEncoded = token.getPrivate().getEncoded();
            credential = new InstallCredential(DEVICE, 42L, Crypto.b64(install.getPrivate().getEncoded()),
                Crypto.b64(Crypto.generateX25519().getPrivate().getEncoded()),
                Crypto.b64(Arrays.copyOfRange(tokenEncoded, tokenEncoded.length - 32, tokenEncoded.length)),
                signed(certificate, false), IDENTITY.uuid(), IDENTITY.username());
            transport = new FakeHttp(this, beta);
            client = new AuthServiceClient(config, IDENTITY, beta ? CATALOG : null, transport, BINDING);
        }

        String signed(JsonObject claims, boolean corrupted) throws Exception {
            String header = Crypto.b64("{\"alg\":\"EdDSA\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8));
            String payload = Crypto.b64(JSON.toJson(claims).getBytes(StandardCharsets.UTF_8));
            byte[] signature = Crypto.sign(server.getPrivate(), (header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            if (corrupted) signature[0] ^= 1;
            return header + "." + payload + "." + Crypto.b64(signature);
        }
    }

    private static final class FakeHttp extends HttpClient {
        private final Fixture fixture;
        private final boolean expectBeta;
        boolean respondBeta;
        String authMutation = "", challengeMutation = "";
        String sessionMutation = "";
        String replayedSession;
        int proofs;
        int protectedRequests;
        int failureStatus;
        String errorCode = "", validateMutation = "";
        long issuedExpiration;
        int validations;
        boolean sawTamper;
        private final byte[] challenge;
        private final java.util.Set<String> dpopIds = new java.util.HashSet<>();

        FakeHttp(Fixture fixture, boolean beta) {
            this.fixture = fixture;
            expectBeta = beta;
            respondBeta = beta;
            JsonObject value=new JsonObject();value.addProperty("product",beta?"celeste_plus":"celeste");value.addProperty("nonce","fixture");
            value.addProperty("hardware",Crypto.b64(java.util.HexFormat.of().parseHex(CORE_HARDWARE)));value.addProperty("protocol",beta?"cst-auth-2":"cst-auth-1");
            if(beta)value.addProperty("hwid_sha256",HWID);challenge=value.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            check(request.uri().getScheme().equals("https") && request.uri().getHost().equals("auth.invalid"), "pinned HTTPS origin");
            if (failureStatus != 0) return (HttpResponse<T>) new Response(request, "{\"error_code\":\""+errorCode+"\",\"request_id\":\"fixture-request\",\"retry_after_seconds\":123}", failureStatus);
            try {
                String path = request.uri().getPath();
                if (path.startsWith("/v1/blob/") || path.startsWith("/v1/loader/") || path.equals("/v1/configs")) {
                    verifyDpop(request);
                    protectedRequests++;
                    return (HttpResponse<T>) new ByteResponse(request, new byte[]{1, 2, 3});
                }
                JsonObject body = JSON.fromJson(readBody(request), JsonObject.class);
                JsonObject response = new JsonObject();
                switch (request.uri().getPath()) {
                    case "/v1/installer/consume" -> {
                        verifyDpop(request);
                        protectedRequests++;
                        response.addProperty("accepted", true);
                    }
                    case "/v1/validate" -> {
                        verifyDpop(request); validations++;
                        check(expectBeta && request.headers().firstValue("X-Device-Id").orElse("").equals(HWID),"validate binds precise v2 device digest");
                        check(readBody(request).equals("{}") && request.headers().firstValue("Content-Type").orElse("").equals("application/json"),"validate JSON wire");
                        response.addProperty("status", validateMutation.equals("status")?"revoked":"valid");
                        response.addProperty("expires_epoch", validateMutation.equals("expiry") ? issuedExpiration-1 : issuedExpiration);
                    }
                    case "/v1/auth/start" -> {
                        check(expectBeta ? body.get("hwid_sha256").getAsString().equals(HWID) : !body.has("hwid_sha256"),"v2 additive hardware only sent by Plus");
                        check(expectBeta ? CATALOG.equals(body.get("beta_catalog_sha256").getAsString()) : !body.has("beta_catalog_sha256"),
                            "auth start carries only expected catalog context");
                        response.addProperty("challenge_id", "fixture-challenge");
                        JsonObject challengeClaims=JSON.fromJson(new String(challenge,StandardCharsets.UTF_8),JsonObject.class);
                        if(!challengeMutation.isEmpty())challengeClaims.addProperty(challengeMutation,"wrong");
                        response.addProperty("message", Crypto.b64(challengeClaims.toString().getBytes(StandardCharsets.UTF_8)));
                    }
                    case "/v1/auth/complete" -> {
                        verifyDpop(request);
                        verifyProofs(body, challenge);
                        JsonObject claims = claims("celeste-client", authMutation);
                        issuedExpiration=claims.get("exp").getAsLong();
                        if (!respondBeta) {
                            var artifact = JSON.fromJson("{\"release\":\"owner-beta-test\",\"sha256\":\"" + DIGEST
                                + "\",\"chunks\":[{\"url\":\"/v1/blob/a\",\"sha256\":\"" + DIGEST
                                + "\"},{\"url\":\"/v1/blob/b\",\"sha256\":\"" + DIGEST + "\"}]}", JsonObject.class);
                            response.add("artifact", artifact);
                            response.add("loader", JSON.fromJson("{\"url\":\"/v1/loader/test\",\"sha256\":\"" + DIGEST + "\"}", JsonObject.class));
                        } else {
                            JsonObject runtime = new JsonObject();
                            runtime.addProperty("release", fixture.config.releaseId());
                            runtime.addProperty("sha256", DIGEST);
                            runtime.addProperty("client_version", fixture.config.clientVersion());
                            runtime.addProperty("catalog_sha256", CATALOG);
                            response.add("runtime", runtime);
                            if (authMutation.equals("artifact")) response.add("artifact", new JsonObject());
                            if (authMutation.equals("stage")) claims.addProperty("stage2", "unexpected-production-loader");
                        }
                        response.addProperty("uid", 42);
                        response.addProperty("grant", fixture.signed(claims, authMutation.equals("signature")));
                    }
                    case "/v1/session/check" -> {
                        verifyDpop(request);
                        check(expectBeta ? CATALOG.equals(body.get("beta_catalog_sha256").getAsString()) : !body.has("beta_catalog_sha256"),
                            "heartbeat catalog presence preserved");
                        String message = "celeste-session-v1\n" + DEVICE + "\n" + body.get("timestamp").getAsLong() + "\n"
                            + body.get("nonce").getAsString() + "\n" + DIGEST + "\n" + body.get("observed_sha256").getAsString()
                            + "\n" + body.get("event").getAsString() + "\n" + IDENTITY.uuid() + "\n" + IDENTITY.username();
                        if (expectBeta) {
                            message += "\nowner-beta-test\n1.0-test\n" + CATALOG + "\nceleste-session-v2\n" + body.get("hardware_sha256").getAsString()
                                + "\n" + body.get("hwid_sha256").getAsString() + "\n" + body.get("process_id").getAsLong() + "\n" + body.get("session_id").getAsString();
                            check(body.get("hardware_sha256").getAsString().equals(CORE_HARDWARE) && body.get("hwid_sha256").getAsString().equals(HWID)
                                && body.get("process_id").getAsLong()==PID && body.get("session_id").getAsString().equals(SESSION),"all current hardware/process values in signed request");
                        }
                        verifyProofs(body, message.getBytes(StandardCharsets.UTF_8));
                        sawTamper = body.get("event").getAsString().equals("integrity_mismatch");
                        JsonObject claims = claims("celeste-session", sessionMutation);
                        claims.addProperty("nonce", sessionMutation.equals("nonce") ? "other-nonce" : body.get("nonce").getAsString());
                        claims.addProperty("status", "active");
                        response.addProperty("status", "active");
                        response.addProperty("session_token", replayedSession != null ? replayedSession
                            : fixture.signed(claims, sessionMutation.equals("signature")));
                    }
                    default -> throw new AssertionError("Unexpected endpoint " + request.uri());
                }
                return (HttpResponse<T>) new Response(request, JSON.toJson(response));
            } catch (GeneralSecurityException exception) {
                throw new AssertionError("Fixture proof verification failed", exception);
            } catch (Exception exception) {
                throw new IOException("Fixture transport failed", exception);
            }
        }

        private JsonObject claims(String audience, String mutation) {
            JsonObject claims = new JsonObject();
            claims.addProperty("aud", audience);
            if (!mutation.equals("missing-product")) claims.addProperty("product",
                (respondBeta != mutation.equals("product")) ? "celeste_plus" : "celeste");
            if (mutation.equals("object-product")) claims.add("product", new JsonObject());
            claims.addProperty("sub", mutation.equals("uid") ? "43" : "42");
            claims.addProperty("device", mutation.equals("device") ? "other-device" : DEVICE);
            claims.addProperty("release", mutation.equals("release") ? "other-release" : fixture.config.releaseId());
            claims.addProperty("client_sha256", mutation.equals("digest") ? OTHER : DIGEST);
            claims.addProperty("nbf", Instant.now().getEpochSecond() - 1);
            claims.addProperty("exp", Instant.now().getEpochSecond() + 60);
            if (!mutation.equals("missing-cnf")) {
                JsonObject cnf = new JsonObject();
                cnf.addProperty("jkt", mutation.equals("wrong-cnf") ? "wrong-key" : fixture.signer.thumbprint());
                claims.add("cnf", cnf);
            }
            if (respondBeta) {
                claims.addProperty("auth_protocol",2);
                if (audience.equals("celeste-session")) {
                    claims.addProperty("hardware_sha256", mutation.equals("hardware")?OTHER:CORE_HARDWARE);
                    claims.addProperty("hwid_sha256", mutation.equals("hwid")?OTHER:HWID);
                    claims.addProperty("process_id", mutation.equals("pid")?PID+1:PID);
                    claims.addProperty("session_id", mutation.equals("session")?"T".repeat(32):SESSION);
                }
                if (!mutation.equals("missing-catalog")) claims.addProperty("beta_catalog_sha256", mutation.equals("catalog") ? OTHER : CATALOG);
                claims.addProperty("client_version", mutation.equals("version") ? "other-version" : fixture.config.clientVersion());
                switch (mutation) {
                    case "malformed-catalog" -> claims.addProperty("beta_catalog_sha256", "invalid-catalog-hash");
                    case "object-catalog" -> claims.add("beta_catalog_sha256", new JsonObject());
                    case "missing-version" -> claims.remove("client_version");
                    case "object-version" -> claims.add("client_version", new JsonObject());
                    case "missing-release" -> claims.remove("release");
                    case "object-release" -> claims.add("release", new JsonObject());
                    default -> { }
                }
            }
            return claims;
        }

        private void verifyDpop(HttpRequest request) throws Exception {
            String[] parts = request.headers().firstValue("DPoP").orElseThrow().split("\\.");
            check(parts.length == 3 && Crypto.verify(fixture.install.getPublic(),
                (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), Crypto.b64(parts[2])), "DPoP installation signature");
            JsonObject header = JSON.fromJson(new String(Crypto.b64(parts[0]), StandardCharsets.UTF_8), JsonObject.class);
            JsonObject proof = JSON.fromJson(new String(Crypto.b64(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
            check(header.get("typ").getAsString().equals("dpop+jwt") && header.get("alg").getAsString().equals("EdDSA"), "DPoP proof type");
            check(header.getAsJsonObject("jwk").get("x").getAsString().equals(Crypto.b64(Crypto.rawPublic(fixture.install.getPublic()))), "DPoP installation public key");
            check(proof.get("htm").getAsString().equals(request.method())
                && proof.get("htu").getAsString().equals("https://auth.invalid" + request.uri().getRawPath()), "DPoP exact request binding without query");
            String authorization = request.headers().firstValue("Authorization").orElse(null);
            if (authorization == null) {
                check(!proof.has("ath"), "issuance proof omits access-token hash");
            } else {
                check(authorization.startsWith("DPoP "), "protected resource never downgrades to Bearer");
                String token = authorization.substring(5);
                check(proof.get("ath").getAsString().equals(Crypto.b64(Crypto.sha256(token.getBytes(StandardCharsets.US_ASCII)))), "proof bound to exact authorization token");
            }
            if (request.uri().getPath().equals("/v1/validate")) check(proof.get("hwid").getAsString().equals(HWID), "device header bound into signed proof");
            check(dpopIds.add(proof.get("jti").getAsString()), "Fresh proof for each request");
            check(Math.abs(Instant.now().getEpochSecond() - proof.get("iat").getAsLong()) <= 5, "DPoP current issuance time");
        }

        private void verifyProofs(JsonObject body, byte[] message) throws Exception {
            check(Crypto.verify(fixture.install.getPublic(), message, Crypto.b64(body.get("install_signature").getAsString())), "installation proof valid");
            check(Crypto.verify(fixture.token.getPublic(), message, Crypto.b64(body.get("token_signature").getAsString())), "secondary token proof valid");
            proofs++;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { try { return SSLContext.getDefault(); } catch (Exception error) { throw new AssertionError(error); } }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) { try { return CompletableFuture.completedFuture(send(request,handler)); } catch(IOException e) { return CompletableFuture.failedFuture(e); } }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new AssertionError("Async network is forbidden"); }
    }

    private record Response(HttpRequest request, String body, int statusCode) implements HttpResponse<String> {
        Response(HttpRequest request, String body) { this(request, body, 200); }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private record ByteResponse(HttpRequest request, byte[] body) implements HttpResponse<byte[]> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("application/octet-stream")), (a, b) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static String readBody(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<String> body = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) { byte[] block = new byte[item.remaining()]; item.get(block); bytes.writeBytes(block); }
            @Override public void onError(Throwable error) { body.completeExceptionally(error); }
            @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return body.join();
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void rejected(Throwing operation, String message) throws Exception {
        try { operation.run(); } catch (GeneralSecurityException | IllegalStateException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void securityRejected(Throwing operation, String message) throws Exception {
        try { operation.run(); } catch (GeneralSecurityException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void statusRejected(Throwing operation, int status, String message) throws Exception {
        try {
            operation.run();
        } catch (AuthServiceClient.AuthRejectedException expected) {
            check(expected.statusCode() == status, "conflict status preserved");
            check(expected.getMessage().equals(message), "conflict guidance matches runtime mode");
            return;
        }
        throw new AssertionError("Expected authentication status " + status);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
