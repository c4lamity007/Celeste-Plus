package dev.c4lam.celeste.auth;

import static dev.c4lam.celeste.auth.SecurityIncidentProtocol.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Map;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class AuthServiceClient implements AutoCloseable {
    record RemoteChunk(String url, String sha256) {
    }

    record RemotePayload(String release, String sha256, List<RemoteChunk> chunks) {
    }

    record RemoteLoader(String url, String sha256) {
    }

    record Authenticated(long uid, String grant, RemotePayload payload, RemoteLoader loader) {
    }

    record CloudResponse(String contentType, byte[] body) {
    }

    record Enrollment(InstallCredential credential) {
    }

    private static final Gson GSON = new Gson();
    private static final byte[] TOKEN_INFO = "celeste-install-token-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int AUTH_RESPONSE_LIMIT = 1_048_576;
    private static final int CLOUD_RESPONSE_LIMIT = 2_000_000;
    private static final int ARTIFACT_RESPONSE_LIMIT = 128 * 1_048_576;
    private final AuthConfig config;
    private final HttpClient http;
    private final PinnedTls.Owner transportOwner;
    private final PublicKey serverKey;
    private final GameIdentity identity;
    private final String expectedBetaCatalog;
    private volatile DpopProofSigner proofSigner;
    private InstallCredential boundCredential;
    private volatile boolean closed;
    private volatile SessionBinding sessionBinding;
    private final java.util.Set<IncidentTransport> incidentTransports = java.util.concurrent.ConcurrentHashMap.newKeySet();
    record SessionBinding(String hardware, String hwid, long pid, String session) {
        SessionBinding {
            if (hardware == null || !hardware.matches("[0-9a-f]{64}") || hwid == null
                || !(hwid.isEmpty() || hwid.matches("[0-9a-f]{64}")) || pid <= 0
                || session == null || !session.matches("[A-Za-z0-9_-]{32}"))
                throw new IllegalArgumentException("Invalid runtime identity");
        }
        String device() { return hwid.isEmpty() ? hardware : hwid; }
        String signedSuffix() { return "\nceleste-session-v2\n" + hardware + "\n" + hwid + "\n" + pid + "\n" + session; }
    }

    AuthServiceClient(AuthConfig config, GameIdentity identity, String catalog, HttpClient http, SessionBinding binding) throws GeneralSecurityException {
        this(config, identity, catalog, http);
        this.sessionBinding = java.util.Objects.requireNonNull(binding);
    }
    static String coreHardwareDigest(HardwareFingerprint hardware) {
        JsonObject core = new JsonObject();
        core.addProperty("board", hardware.board()); core.addProperty("processor", hardware.processor()); core.addProperty("system", hardware.system());
        return Crypto.sha256(GSON.toJson(core));
    }
    private synchronized SessionBinding binding(HardwareFingerprint hardware) {
        if (sessionBinding == null) sessionBinding = new SessionBinding(coreHardwareDigest(hardware == null ? HardwareFingerprint.collect() : hardware),
            HardwareIdentityV2.current(), ProcessHandle.current().pid(), dev.celestebeta.host.ResourceReloadBridge.processSessionId());
        return sessionBinding;
    }
    private void addHardwareIdentity(JsonObject body, HardwareFingerprint hardware) {
        if (expectedBetaCatalog != null) {
            SessionBinding value = binding(hardware);
            if (!value.hwid().isEmpty()) body.addProperty("hwid_sha256", value.hwid());
        }
    }

    AuthServiceClient(AuthConfig config, GameIdentity identity) throws GeneralSecurityException {
        this(config, identity, null);
    }

    AuthServiceClient(AuthConfig config, GameIdentity identity, String expectedBetaCatalog)
        throws GeneralSecurityException {
        this(config, identity, expectedBetaCatalog, null);
    }

    /** Package-local transport seam for protocol fixtures; runtime always uses the HTTPS client above. */
    AuthServiceClient(AuthConfig config, GameIdentity identity, String expectedBetaCatalog, HttpClient http)
        throws GeneralSecurityException {
        if (expectedBetaCatalog != null && !expectedBetaCatalog.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected beta catalog hash is invalid");
        }
        this.config = config;
        this.identity = identity;
        this.expectedBetaCatalog = expectedBetaCatalog;
        this.serverKey = Crypto.ed25519Public(config.serverKey());
        this.http = http;
        this.transportOwner = http == null ? PinnedTls.acquire(config.baseUri(), config.serverKey(), product()) : null;
    }

    /** The catalog pin is an integrity-covered resident resource, never a server-selected fallback. */
    dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient moduleDelivery(
            dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.SessionSource sessions) throws Exception {
        byte[] pin;
        try (java.io.InputStream input=AuthServiceClient.class.getResourceAsStream("/META-INF/celeste-beta/module-executables.json")) {
            if(input==null)return null; // Legacy release artifact; modular packaging requires the resource.
            pin=input.readNBytes(512*1024+1);
        }
        if(expectedBetaCatalog==null || !"celeste_plus".equals(product()) || pin.length>512*1024)
            throw new GeneralSecurityException("Invalid modular runtime trust context");
        DpopProofSigner signer=proofSigner;
        if(signer==null || signer.isDestroyed())throw new GeneralSecurityException("Installation proof unavailable");
        try {
            return new dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient(config.baseUri(),config.serverKey(),signer,sessions,
                config.releaseDigest(),expectedBetaCatalog,pin,
                java.nio.file.Path.of(System.getProperty("user.home"), ".celeste-plus", "module-cache"),this::moduleRequestEvidence);
        } finally { Arrays.fill(pin,(byte)0); }
    }

    /** Every module request measures the current identity and binds both enrolled keys. */
    private dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.RequestEvidence moduleRequestEvidence()
            throws GeneralSecurityException, IOException {
        InstallCredential credential=boundCredential;
        if(closed || credential==null || credential.isDestroyed())
            throw new GeneralSecurityException("Installation credentials are unavailable");
        if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("windows"))
            throw new GeneralSecurityException("Independent modules require the reviewed Windows hardware identity");
        try {
            HardwareIdentityV2.ModuleHardware measured=HardwareIdentityV2.freshWindowsEvidence();
            HardwareFingerprint hardware=measured.fingerprint();
            return dev.c4lam.celeste.beta.delivery.ModuleDeliveryClient.evidence(hardware.system(),hardware.board(),
                hardware.processor(),hardware.monitors(),measured.hwid(),ArtifactIntegrity.digest(),
                credential::signToken);
        } catch (RuntimeException invalid) {
            throw new GeneralSecurityException("Current module hardware identity is unavailable",invalid);
        }
    }

    private HttpClient transport() throws IOException, InterruptedException {
        if (closed) throw new IOException("Authentication transport is closed");
        if (http != null) return http; // Package-local deterministic protocol fixture only.
        return transportOwner.client();
    }

    /** Full incident signature/context/decision gate; selected as an actual native body. */
    static Decision verifyIncidentDecision(PublicKey serverKey, Request request, String compact,
                           long nowEpochSeconds, long nowNanos) throws GeneralSecurityException {
        try {
            if (compact == null || compact.length()>8192) throw invalid();
            String[] parts=compact.split("\\.",-1);
            if(parts.length!=3)throw invalid();
            byte[] header=decode(parts[0]), payload=decode(parts[1]), signature=decode(parts[2]);
            if(signature.length!=64)throw invalid();
            Signature verifier=Signature.getInstance("Ed25519"); verifier.initVerify(serverKey);
            verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
            if(!verifier.verify(signature))throw invalid();
            Map<String,String> h=object(header);
            if(!h.equals(Map.of("alg","EdDSA","kid","celeste-release-1","typ","CST")))throw invalid();
            Map<String,String> c=object(payload); Binding b=request.binding(); Observation o=request.observation();
            expect(c,"aud","celeste-security-incident"); expect(c,"product","celeste_plus");
            expect(c,"sub",Long.toString(b.account())); expect(c,"device",b.device());
            expect(c,"process_id",Long.toString(b.process())); expect(c,"session_id",b.session());
            expect(c,"release",b.release()); expect(c,"beta_catalog_sha256",b.catalog());
            expect(c,"client_sha256",b.hostDigest()); expect(c,"scope",b.scope()==Scope.PLUS?"celeste_plus":"license");
            expect(c,"incident_id",request.incident()); expect(c,"request_nonce",request.nonce());
            expect(c,"phase",request.phase().name().toLowerCase(java.util.Locale.ROOT));
            expect(c,"first_observation_id",request.firstObservationId());expect(c,"observation_id",request.sample().id());
            expect(c,"observed_at_ms",Long.toString(request.sample().atMillis()));
            expect(c,"signal",o.kind().name().toLowerCase(java.util.Locale.ROOT));
            expect(c,"artifact",o.artifact()); expect(c,"expected_sha256",o.expected()); expect(c,"observed_sha256",o.observed());
            long issued=number(c,"iat"), expiration=number(c,"exp"), notBefore=number(c,"nbf");
            if(issued<0 || issued>nowEpochSeconds+5 || expiration<=nowEpochSeconds || expiration<=issued
                    || expiration-issued>90 || notBefore>nowEpochSeconds+5 || notBefore>issued)throw invalid();
            String challenge=c.get("challenge_id"); if(!opaque(challenge,22,64))throw invalid();
            Status status;
            try {status=Status.valueOf(c.get("status").toUpperCase(java.util.Locale.ROOT));}
            catch(RuntimeException invalidStatus){throw invalid();}
            if(request.phase()==Phase.BEGIN&&status!=Status.WARNING
                    ||request.phase()==Phase.CONFIRM&&status!=Status.REVOKED
                    ||request.phase()==Phase.OUTCOME&&status!=Status.PENDING&&status!=Status.REVOKED)throw invalid();
            if(request.phase()!=Phase.BEGIN && !request.challenge().equals(challenge))throw invalid();
            long confirm=number(c,"confirm_after");
            if(status==Status.WARNING && (confirm<issued+5 || confirm>=expiration))throw invalid();
            if(status!=Status.WARNING && confirm>nowEpochSeconds)throw invalid();
            // Differences are bounded before multiplication; subtraction comparison
            // is safe across nanoTime wraparound for these <=90-second intervals.
            long remaining=Math.min(90,expiration-nowEpochSeconds);
            long delay=Math.max(0,Math.min(90,confirm-nowEpochSeconds));
            return new Decision(challenge,status,nowNanos+delay*1_000_000_000L,nowNanos+remaining*1_000_000_000L);
        } catch(IOException | IllegalArgumentException | IllegalStateException | ArithmeticException error) { throw invalid(); }
    }

    /** Punitive reporting is absent unless this exact signed session opts in. */
    synchronized SecurityIncidentProtocol.Scope incidentScope(String verifiedSession) throws GeneralSecurityException {
        JsonObject claims = incidentClaims(verifiedSession);
        if (!claims.has("security_incident_scope")) return null;
        try {
            return switch (requiredString(claims, "security_incident_scope")) {
                case "celeste_plus" -> SecurityIncidentProtocol.Scope.PLUS;
                case "license" -> SecurityIncidentProtocol.Scope.LICENSE;
                default -> throw new GeneralSecurityException("Unrecognized security incident policy");
            };
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw new GeneralSecurityException("Malformed security incident policy", malformed);
        }
    }

    private JsonObject incidentClaims(String token) throws GeneralSecurityException {
        if (closed || expectedBetaCatalog == null || boundCredential == null || boundCredential.isDestroyed()
                || proofSigner == null || proofSigner.isDestroyed())
            throw new GeneralSecurityException("Incident reporting requires an active Plus installation");
        JsonObject claims = verifyToken(token, "celeste-session");
        try {
            requireProduct(claims); requireBetaContext(claims); proofSigner.requireBinding(claims);
            SessionBinding current = binding(null);
            if (!"active".equals(requiredString(claims, "status"))
                    || !Long.toString(boundCredential.uid()).equals(requiredString(claims, "sub"))
                    || !boundCredential.deviceId().equals(requiredString(claims, "device"))
                    || !config.releaseDigest().equals(requiredHex(claims, "client_sha256"))
                    || !current.hardware().equals(requiredHex(claims, "hardware_sha256"))
                    || !current.hwid().equals(requiredHex(claims, "hwid_sha256"))
                    || current.pid() != requiredLong(claims, "process_id")
                    || !current.session().equals(requiredString(claims, "session_id")))
                throw new GeneralSecurityException("Incident session binding differs");
            return claims;
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            throw new GeneralSecurityException("Malformed incident session binding", malformed);
        }
    }

    @FunctionalInterface interface IncidentHardware {
        SecurityIncidentProtocol.Hardware collect() throws GeneralSecurityException;
    }

    private static SecurityIncidentProtocol.Hardware currentIncidentHardware() throws GeneralSecurityException {
        try {
            HardwareIdentityV2.ModuleHardware measured = HardwareIdentityV2.freshWindowsEvidence();
            HardwareFingerprint hardware = measured.fingerprint();
            java.util.TreeSet<String> monitors = new java.util.TreeSet<>(hardware.monitors());
            if (monitors.isEmpty() || monitors.size() > 8
                    || monitors.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}")))
                throw new GeneralSecurityException("Current monitor evidence unavailable");
            JsonArray values = new JsonArray(); monitors.forEach(values::add);
            JsonObject document = new JsonObject(); document.add("monitors", values);
            return new SecurityIncidentProtocol.Hardware(coreHardwareDigest(hardware),
                Crypto.sha256(GSON.toJson(document)), measured.hwid());
        } catch (RuntimeException unavailable) {
            throw new GeneralSecurityException("Current incident hardware unavailable", unavailable);
        }
    }

    IncidentTransport incidentTransport(InstallCredential credential, String verifiedSession, long originalDeadline)
            throws GeneralSecurityException {
        return incidentTransport(credential, verifiedSession, originalDeadline, AuthServiceClient::currentIncidentHardware);
    }

    /** Package-local hardware seam; production always uses a fresh firmware/monitor measurement. */
    synchronized IncidentTransport incidentTransport(InstallCredential credential, String verifiedSession,
            long originalDeadline, IncidentHardware hardware) throws GeneralSecurityException {
        SecurityIncidentProtocol.Scope scope = incidentScope(verifiedSession);
        if (scope == null || credential != boundCredential)
            throw new GeneralSecurityException("Incident reporting is not enabled for this installation");
        long now = System.nanoTime(), remaining = originalDeadline - now;
        if (remaining <= 0 || remaining > 90_000_000_000L)
            throw new GeneralSecurityException("Original incident lease is unavailable");
        long deadline = now + Math.min(remaining, sessionValidityNanos(verifiedSession));
        JsonObject certificate = verifyToken(credential.certificate(), "celeste-installation");
        DpopProofSigner signer = credential.signer(Crypto.b64(requiredString(certificate, "install_key")));
        try {
            signer.requireBinding(incidentClaims(verifiedSession));
            IncidentTransport reporter = new IncidentTransport(credential, signer, verifiedSession, deadline, scope, hardware);
            incidentTransports.add(reporter);
            return reporter;
        } catch (GeneralSecurityException | RuntimeException | Error failure) { signer.close(); throw failure; }
    }

    /** One incident, one original host lease; no gameplay or delivery authority is conferred here. */
    final class IncidentTransport implements SecurityIncidentController.Transport, AutoCloseable {
        private final InstallCredential credential;
        private final DpopProofSigner signer;
        private final IncidentHardware hardware;
        private final SecurityIncidentProtocol.Binding context;
        private final long originalDeadline;
        private final java.util.concurrent.ThreadPoolExecutor worker;
        private String token, incident;
        private SecurityIncidentProtocol.Hardware originalHardware;
        private IncidentCall active;
        private long outcomeDeadline;
        private boolean retired, confirmationSent;

        private IncidentTransport(InstallCredential credential, DpopProofSigner signer, String token, long deadline,
                SecurityIncidentProtocol.Scope scope, IncidentHardware hardware) {
            this.credential = credential; this.signer = signer; this.token = token;
            this.originalDeadline = deadline; this.hardware = java.util.Objects.requireNonNull(hardware);
            SessionBinding current = AuthServiceClient.this.binding(null);
            this.context = new SecurityIncidentProtocol.Binding(credential.uid(), credential.deviceId(), current.pid(),
                current.session(), config.releaseId(), expectedBetaCatalog, config.releaseDigest(), scope);
            worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "celeste-incident-report");
                    thread.setDaemon(true); thread.setContextClassLoader(null); return thread;
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        }

        SecurityIncidentProtocol.Binding binding() { return context; }

        @Override public synchronized SecurityIncidentController.Call report(SecurityIncidentProtocol.Request request, long deadline) {
            IncidentCall call = new IncidentCall(request, deadline);
            try {
                if (active != null && !active.result.isDone()) throw new IOException("Incident request already in progress");
                check(call);
                if (incident == null) {
                    if (request.phase() != SecurityIncidentProtocol.Phase.BEGIN) throw new IOException("Incident has not begun");
                    incident = request.incident();
                }
                if (!incident.equals(request.incident())) throw new IOException("Incident identity changed");
                active = call;
                long remaining = call.deadline - System.nanoTime();
                call.result.orTimeout(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                call.work = worker.submit(call::run);
                if (call.result.isDone()) call.stop();
            } catch (IOException | RuntimeException failure) { call.result.completeExceptionally(failure); }
            return call;
        }

        private synchronized void check(IncidentCall call) throws IOException {
            long now = System.nanoTime();
            boolean outcome = call.request.phase() == SecurityIncidentProtocol.Phase.OUTCOME;
            long limit = outcome ? outcomeDeadline : originalDeadline;
            if (retired || closed || credential.isDestroyed() || signer.isDestroyed() || call.result.isDone()
                    || !context.equals(call.request.binding()) || outcome && !confirmationSent
                    || limit - now <= 0 || call.deadline - now <= 0)
                throw new IOException("Incident request is canceled, expired or belongs to another session");
            call.deadline = now + Math.min(5_000_000_000L, Math.min(call.deadline - now, limit - now));
        }

        @Override public void close() {
            IncidentCall pending;
            synchronized (this) {
                if (retired) return;
                retired = true; pending = active; active = null; token = null; originalHardware = null;
            }
            if (pending != null) pending.cancel();
            worker.shutdownNow(); signer.close(); incidentTransports.remove(this);
        }

        private final class IncidentCall implements SecurityIncidentController.Call {
            final SecurityIncidentProtocol.Request request;
            final CompletableFuture<String> result = new CompletableFuture<>();
            volatile long deadline;
            volatile java.util.concurrent.Future<?> work;
            volatile CompletableFuture<HttpResponse<byte[]>> network;
            IncidentCall(SecurityIncidentProtocol.Request request, long deadline) {
                this.request = java.util.Objects.requireNonNull(request); this.deadline = deadline;
                result.whenComplete((value, failure) -> stop());
            }
            @Override public CompletionStage<String> response() { return result; }
            @Override public void cancel() { result.cancel(true); }
            void stop() {
                var pending = network; if (pending != null) pending.cancel(true);
                var task = work;
                if (task != null) { task.cancel(true); if (task instanceof Runnable runnable) worker.remove(runnable); }
            }
            void run() {
                byte[] body = null, signature = null, message = null, responseBytes = null;
                try {
                    check(this);
                    SecurityIncidentProtocol.Hardware measured;
                    if (request.phase() == SecurityIncidentProtocol.Phase.OUTCOME) {
                        synchronized (IncidentTransport.this) { measured = originalHardware; }
                        if (measured == null) throw new IOException("Original incident evidence unavailable");
                    } else measured = hardware.collect();
                    check(this);
                    SessionBinding current = AuthServiceClient.this.binding(null);
                    if (!current.hardware().equals(measured.core()) || !current.hwid().equals(measured.hwid()))
                        throw new GeneralSecurityException("Incident device identity changed");
                    String session;
                    synchronized (IncidentTransport.this) {
                        check(this);
                        if (originalHardware == null) originalHardware = measured;
                        session = token;
                    }
                    body = SecurityIncidentProtocol.body(request, measured);
                    URI endpoint = sameOrigin("/v1/security/incidents");
                    String proof = signer.proofForIncident("POST", endpoint, session, measured.hwid(), body);
                    message = ("celeste-incident-proof-v1\n" + proof).getBytes(StandardCharsets.US_ASCII);
                    signature = credential.signToken(message);
                    HttpClient client = transport();
                    check(this);
                    HttpRequest outgoing = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofNanos(deadline - System.nanoTime()))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .header("Authorization", "DPoP " + session).header("DPoP", proof)
                        .header("X-Celeste-Incident-Token-Proof", Crypto.b64(signature))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                    check(this);
                    network = client.sendAsync(outgoing, boundedBytes(16_384));
                    synchronized (IncidentTransport.this) {
                        if (request.phase() == SecurityIncidentProtocol.Phase.CONFIRM) {
                            confirmationSent = true;
                            long now = System.nanoTime();
                            outcomeDeadline = now + Math.min(10_000_000_000L, originalDeadline + 10_000_000_000L - now);
                        }
                    }
                    if (result.isDone()) network.cancel(true);
                    HttpResponse<byte[]> incoming = network.get(Math.max(1, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
                    responseBytes = incoming.body();
                    check(this);
                    if (!endpoint.equals(incoming.uri()) || incoming.statusCode() != 200 || responseBytes == null || responseBytes.length > 16_384
                            || !incoming.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].trim().equalsIgnoreCase("application/json"))
                        throw new IOException("Incident service response rejected");
                    String decision;
                    try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(
                            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(responseBytes)).toString()))) {
                        reader.setStrictness(com.google.gson.Strictness.STRICT);
                        reader.beginObject();
                        if (!reader.hasNext() || !reader.nextName().equals("decision")
                                || reader.peek() != com.google.gson.stream.JsonToken.STRING)
                            throw new GeneralSecurityException("Malformed incident response");
                        decision = reader.nextString();
                        if (reader.hasNext()) throw new GeneralSecurityException("Unexpected incident response field");
                        reader.endObject();
                        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
                            throw new GeneralSecurityException("Trailing incident response content");
                    }
                    if (decision.length() > 8192) throw new GeneralSecurityException("Oversized incident decision");
                    check(this); result.complete(decision);
                } catch (Exception failure) { result.completeExceptionally(failure); }
                finally {
                    if (body != null) Arrays.fill(body, (byte) 0);
                    if (signature != null) Arrays.fill(signature, (byte) 0);
                    if (message != null) Arrays.fill(message, (byte) 0);
                    if (responseBytes != null) Arrays.fill(responseBytes, (byte) 0);
                    Thread.interrupted();
                }
            }
        }
    }

    Enrollment enroll(String licenseKey, HardwareFingerprint hardware) throws Exception {
        KeyPair installSigning = Crypto.generateEd25519();
        KeyPair installEncryption = null;
        try {
        installEncryption = Crypto.generateX25519();
        try (DpopProofSigner enrollmentProof = new DpopProofSigner(installSigning.getPrivate(), Crypto.rawPublic(installSigning.getPublic()))) {

        JsonObject startBody = releaseBody();
        startBody.addProperty("license_key", licenseKey.strip());
        startBody.addProperty("install_signing_key", Crypto.b64(Crypto.rawPublic(installSigning.getPublic())));
        startBody.addProperty("install_encryption_key", Crypto.b64(Crypto.rawPublic(installEncryption.getPublic())));
        startBody.add("hardware", hardwareJson(hardware));
        addHardwareIdentity(startBody, hardware);
        JsonObject start = post("/v1/enroll/start", startBody);
        byte[] message = requiredB64(start, "message");
        requireChallenge(GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class), hardware);

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", requiredString(start, "challenge_id"));
        completeBody.addProperty("install_signature", Crypto.b64(Crypto.sign(installSigning.getPrivate(), message)));
        JsonObject complete = post("/v1/enroll/complete", completeBody, enrollmentProof);

        String deviceId = requiredString(complete, "device_id");
        long uid = requiredLong(complete, "uid");
        String certificate = requiredString(complete, "device_certificate");
        JsonObject certificateClaims = verifyToken(certificate, "celeste-installation");
        enrollmentProof.requireBinding(certificateClaims);
        if (!deviceId.equals(requiredString(certificateClaims, "device"))
            || uid != Long.parseLong(requiredString(certificateClaims, "sub"))
            || !Crypto.b64(Crypto.rawPublic(installSigning.getPublic())).equals(requiredString(certificateClaims, "install_key"))) {
            throw new GeneralSecurityException("Installation certificate does not match this device");
        }

        byte[] tokenSeed = null, signingEncoding = null, encryptionEncoding = null;
        try {
        tokenSeed = decryptToken(
            requiredObject(complete, "token_envelope"),
            installEncryption.getPrivate(),
            message
        );
        signingEncoding = installSigning.getPrivate().getEncoded();
        encryptionEncoding = installEncryption.getPrivate().getEncoded();
            InstallCredential credential = new InstallCredential(
                deviceId,
                uid,
                signingEncoding,
                encryptionEncoding,
                tokenSeed,
                certificate,
                identity.uuid(),
                identity.username()
            );
            try { credential.validate(); return new Enrollment(credential); }
            catch (Exception | Error failure) { credential.close(); throw failure; }
        } finally {
            if (tokenSeed != null) Arrays.fill(tokenSeed, (byte) 0);
            if (signingEncoding != null) Arrays.fill(signingEncoding, (byte) 0);
            if (encryptionEncoding != null) Arrays.fill(encryptionEncoding, (byte) 0);
        }
        }
        } finally {
            CredentialMaterial.destroy(installSigning.getPrivate());
            if (installEncryption != null) CredentialMaterial.destroy(installEncryption.getPrivate());
        }
    }

    Authenticated authenticate(InstallCredential credential, HardwareFingerprint hardware) throws Exception {
        credential.validate();
        DpopProofSigner signer = bindCredential(credential);
        JsonObject startBody = releaseBody();
        startBody.addProperty("device_id", credential.deviceId());
        if (expectedBetaCatalog != null) {
            startBody.addProperty("beta_catalog_sha256", expectedBetaCatalog);
        }
        startBody.add("hardware", hardwareJson(hardware));
        addHardwareIdentity(startBody, hardware);
        JsonObject start = post("/v1/auth/start", startBody);
        byte[] message = requiredB64(start, "message");
        requireChallenge(GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class), hardware);

        JsonObject completeBody = new JsonObject();
        completeBody.addProperty("challenge_id", requiredString(start, "challenge_id"));
        completeBody.addProperty("install_signature", Crypto.b64(credential.signInstallation(message)));
        completeBody.addProperty("token_signature", Crypto.b64(credential.signToken(message)));
        JsonObject complete = post("/v1/auth/complete", completeBody, signer);
        String grant = requiredString(complete, "grant");
        long uid = requiredLong(complete, "uid");
        JsonObject claims = verifyToken(grant, "celeste-client");
        signer.requireBinding(claims);
        requireProduct(claims);
        if (expectedBetaCatalog != null) {
            requireBetaContext(claims);
            if (requiredLong(claims, "auth_protocol") != 2) throw new GeneralSecurityException("Startup authorization requires the current protocol");
        }
        if (uid != credential.uid()
            || uid != Long.parseLong(requiredString(claims, "sub"))
            || !credential.deviceId().equals(requiredString(claims, "device"))
            || !config.releaseId().equals(requiredString(claims, "release"))
            || !config.releaseDigest().equals(requiredString(claims, "client_sha256"))) {
            throw new GeneralSecurityException("Session grant does not match this installation");
        }
        if (expectedBetaCatalog != null) {
            JsonObject runtime = requiredObject(complete, "runtime");
            if (!config.releaseId().equals(requiredString(runtime, "release"))
                || !config.releaseDigest().equals(requiredHex(runtime, "sha256"))
                || !config.clientVersion().equals(requiredString(runtime, "client_version"))
                || !expectedBetaCatalog.equals(requiredHex(runtime, "catalog_sha256"))
                || complete.has("artifact") || complete.has("loader")
                || claims.has("chunks") || claims.has("stage2") || claims.has("stage2_sha256")) {
                throw new GeneralSecurityException("Beta runtime grant does not match the verified catalog");
            }
            // The beta host already supplied both verified parts. This grant
            // must never be usable as a normal bootstrap/download authorization.
            validateStartupGrant(grant, signer, binding(hardware), claims);
            return new Authenticated(uid, grant, null, null);
        }
        if (complete.has("runtime") || claims.has("beta_catalog_sha256")) {
            throw new GeneralSecurityException("Beta authorization requires its verified catalog context");
        }
        JsonObject artifact = requiredObject(complete, "artifact");
        JsonArray rawChunks = artifact.getAsJsonArray("chunks");
        if (rawChunks == null || rawChunks.size() != 2) {
            throw new GeneralSecurityException("Payload manifest is incomplete");
        }
        List<RemoteChunk> chunks = rawChunks.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .map(chunk -> new RemoteChunk(requiredString(chunk, "url"), requiredHex(chunk, "sha256")))
            .toList();
        RemotePayload payload = new RemotePayload(
            requiredString(artifact, "release"),
            requiredHex(artifact, "sha256"),
            chunks
        );
        JsonObject rawLoader = requiredObject(complete, "loader");
        RemoteLoader loader = new RemoteLoader(
            requiredString(rawLoader, "url"),
            requiredHex(rawLoader, "sha256")
        );
        if (!config.releaseId().equals(payload.release()) || !config.releaseDigest().equals(payload.sha256())) {
            throw new GeneralSecurityException("Payload manifest does not match the authorized release");
        }
        return new Authenticated(uid, grant, payload, loader);
    }

    private void validateStartupGrant(String grant, DpopProofSigner signer, SessionBinding binding, JsonObject claims) throws Exception {
        URI endpoint = sameOrigin("/v1/validate");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("Authorization", "DPoP " + grant).header("X-Device-Id", binding.device())
            .header("DPoP", signer.proofForDevice("POST", endpoint, grant, binding.device()))
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.US_ASCII)).build();
        HttpResponse<String> response = sendAuth(request);
        if (response.statusCode() != 200) throw rejected(response.statusCode(), response.body());
        try {
            JsonObject value = GSON.fromJson(response.body(), JsonObject.class);
            long expiration = requiredLong(value, "expires_epoch");
            if (!"valid".equals(requiredString(value, "status")) || expiration != requiredLong(claims, "exp")
                || expiration <= Instant.now().getEpochSecond()) throw new GeneralSecurityException("Startup grant is no longer valid");
        } catch (RuntimeException error) { throw new GeneralSecurityException("Invalid startup grant validation", error); }
    }

    void consumeInstallerTicket(String ticket, InstallCredential credential) throws Exception {
        DpopProofSigner signer = bindCredential(credential);
        JsonObject claims = verifyToken(ticket, "celeste-installer");
        signer.requireBinding(claims);
        requireProduct(claims);
        if (!credential.deviceId().equals(requiredString(claims, "device"))
            || credential.uid() != Long.parseLong(requiredString(claims, "sub"))
            || requiredHex(claims, "client_sha256").isBlank()
            || !config.releaseId().equals(requiredString(claims, "payload_release"))
            || !config.releaseDigest().equals(requiredHex(claims, "payload_sha256"))
            || requiredString(claims, "bootstrap").isBlank()
            || requiredHex(claims, "bootstrap_sha256").isBlank()) {
            throw new GeneralSecurityException("Native launch ticket does not match this installation");
        }
        URI endpoint = sameOrigin("/v1/installer/consume");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Authorization", "DPoP " + ticket)
            .header("DPoP", signer.proof("POST", endpoint, ticket))
            .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.US_ASCII))
            .build();
        HttpResponse<String> response = sendAuth(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Native launch authorization was rejected");
        }
        try {
            JsonObject accepted = GSON.fromJson(response.body(), JsonObject.class);
            JsonElement value = accepted == null ? null : accepted.get("accepted");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()
                || !value.getAsBoolean()) {
                throw new GeneralSecurityException("Native launch authorization was not accepted");
            }
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed native launch authorization", exception);
        }
    }

    byte[] download(String path, String grant) throws IOException, InterruptedException, AuthRejectedException {
        URI endpoint = sameOrigin(path);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/octet-stream")
            .header("Authorization", "DPoP " + grant)
            .header("DPoP", requestProof("GET", endpoint, grant))
            .GET()
            .build();
        HttpResponse<byte[]> response = transport().send(request, boundedBytes(ARTIFACT_RESPONSE_LIMIT));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Protected artifact download failed");
        }
        return response.body();
    }

    String checkSession(InstallCredential credential, String observedDigest, boolean integrityMismatch) throws Exception {
        try {
            return checkSessionResponse(credential, observedDigest, integrityMismatch);
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed session verification response", exception);
        }
    }

    private String checkSessionResponse(InstallCredential credential, String observedDigest, boolean integrityMismatch)
        throws Exception {
        credential.validate();
        DpopProofSigner signer = bindCredential(credential);
        long timestamp = Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[18];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Crypto.b64(nonceBytes);
        String event = integrityMismatch ? "integrity_mismatch" : "heartbeat";
        byte[] message = sessionMessage(
            credential.deviceId(),
            timestamp,
            nonce,
            config.releaseDigest(),
            observedDigest,
            event
        );

        JsonObject body = releaseBody();
        body.addProperty("device_id", credential.deviceId());
        body.addProperty("timestamp", timestamp);
        body.addProperty("nonce", nonce);
        body.addProperty("observed_sha256", observedDigest);
        body.addProperty("event", event);
        if (expectedBetaCatalog != null) {
            body.addProperty("beta_catalog_sha256", expectedBetaCatalog);
            SessionBinding bound = binding(null);
            body.addProperty("hardware_sha256", bound.hardware());
            if (!bound.hwid().isEmpty()) body.addProperty("hwid_sha256", bound.hwid());
            body.addProperty("process_id", bound.pid()); body.addProperty("session_id", bound.session());
        }
        body.addProperty("install_signature", Crypto.b64(credential.signInstallation(message)));
        body.addProperty("token_signature", Crypto.b64(credential.signToken(message)));
        JsonObject response = post("/v1/session/check", body, signer);
        if (integrityMismatch) {
            throw new GeneralSecurityException("Client integrity verification failed");
        }
        String sessionToken = requiredString(response, "session_token");
        JsonObject claims = verifyToken(sessionToken, "celeste-session");
        signer.requireBinding(claims);
        requireProduct(claims);
        if (!"active".equals(requiredString(response, "status"))
            || !"active".equals(requiredString(claims, "status"))
            || !nonce.equals(requiredString(claims, "nonce"))
            || !credential.deviceId().equals(requiredString(claims, "device"))
            || credential.uid() != Long.parseLong(requiredString(claims, "sub"))
            || !config.releaseDigest().equals(requiredString(claims, "client_sha256"))) {
            throw new GeneralSecurityException("Session verification response did not match this installation");
        }
        if (expectedBetaCatalog != null) {
            requireBetaContext(claims);
            SessionBinding bound = binding(null);
            if (!bound.hardware().equals(requiredHex(claims, "hardware_sha256"))
                || bound.pid() != requiredLong(claims, "process_id") || !bound.session().equals(requiredString(claims, "session_id"))
                || (bound.hwid().isEmpty() ? claims.has("hwid_sha256") : !bound.hwid().equals(requiredHex(claims, "hwid_sha256"))))
                throw new GeneralSecurityException("Session identity binding differs");
        } else if (claims.has("beta_catalog_sha256")) {
            throw new GeneralSecurityException("Unexpected beta session context");
        }
        return sessionToken;
    }

    private void requireBetaContext(JsonObject claims) throws GeneralSecurityException {
        try {
            if (!expectedBetaCatalog.equals(requiredHex(claims, "beta_catalog_sha256"))
                || !config.releaseId().equals(requiredString(claims, "release"))
                || !config.clientVersion().equals(requiredString(claims, "client_version"))) {
                throw new GeneralSecurityException("Beta session belongs to another verified catalog");
            }
        } catch (IllegalStateException | IllegalArgumentException exception) {
            // A malformed signed context is an authentication failure, not a
            // transient transport outage eligible for the heartbeat grace period.
            throw new GeneralSecurityException("Malformed beta session context", exception);
        }
    }

    /** Monotonic lease duration derived only from the verified server expiration. */
    long sessionValidityNanos(String sessionToken) throws GeneralSecurityException {
        try {
            JsonObject claims = verifyToken(sessionToken, "celeste-session");
            Instant now = Instant.now();
            Instant expiration = Instant.ofEpochSecond(requiredLong(claims, "exp"));
            if (!expiration.isAfter(now)) throw new GeneralSecurityException("Server session already expired");
            Duration remaining = Duration.between(now, expiration);
            return remaining.compareTo(Duration.ofSeconds(90)) >= 0
                ? java.util.concurrent.TimeUnit.SECONDS.toNanos(90) : remaining.toNanos();
        } catch (DateTimeException | IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed server session expiration", exception);
        }
    }

    CloudResponse cloudRequest(String method, String path, String contentType, byte[] body, String grant)
        throws IOException, InterruptedException, AuthRejectedException {
        if (path == null || !path.startsWith("/v1/configs") || path.contains("..")) {
            throw new IOException("Cloud config path is invalid");
        }
        String verb = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (!verb.equals("GET") && !verb.equals("POST")) {
            throw new IOException("Cloud config method is invalid");
        }
        URI endpoint = sameOrigin(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json, image/png")
            .header("Authorization", "DPoP " + grant)
            .header("DPoP", requestProof(verb, endpoint, grant));
        if (verb.equals("POST")) {
            builder.header("Content-Type", contentType == null ? "application/json" : contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
        } else {
            builder.GET();
        }
        HttpResponse<byte[]> response = transport().send(builder.build(), boundedBytes(CLOUD_RESPONSE_LIMIT));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AuthRejectedException(response.statusCode(), "Cloud config request failed (" + response.statusCode() + ")");
        }
        return new CloudResponse(response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    private byte[] sessionMessage(
        String deviceId,
        long timestamp,
        String nonce,
        String expectedDigest,
        String observedDigest,
        String event
    ) {
        String message = "celeste-session-v1\n"
            + deviceId + "\n" + timestamp + "\n" + nonce + "\n"
            + expectedDigest + "\n" + observedDigest + "\n" + event + "\n"
            + identity.uuid() + "\n" + identity.username();
        if (expectedBetaCatalog != null) {
            message += "\n" + config.releaseId() + "\n" + config.clientVersion() + "\n" + expectedBetaCatalog + binding(null).signedSuffix();
        }
        return message.getBytes(StandardCharsets.UTF_8);
    }

    private String product() { return expectedBetaCatalog == null ? "celeste" : "celeste_plus"; }

    private void requireChallenge(JsonObject claims, HardwareFingerprint hardware) throws GeneralSecurityException {
        requireProduct(claims);
        if (expectedBetaCatalog == null) return;
        try {
            SessionBinding bound = binding(hardware);
            if (!Crypto.b64(java.util.HexFormat.of().parseHex(bound.hardware())).equals(requiredString(claims, "hardware"))
                || (bound.hwid().isEmpty() ? !"cst-auth-1".equals(requiredString(claims,"protocol")) || claims.has("hwid_sha256")
                    : !"cst-auth-2".equals(requiredString(claims,"protocol")) || !bound.hwid().equals(requiredHex(claims,"hwid_sha256"))))
                throw new GeneralSecurityException("Authentication challenge has another hardware binding");
        } catch (RuntimeException error) { throw new GeneralSecurityException("Invalid authentication hardware challenge", error); }
    }

    private void requireProduct(JsonObject claims) throws GeneralSecurityException {
        try {
            if (claims == null || !product().equals(requiredString(claims, "product"))) {
                throw new GeneralSecurityException("Authorization belongs to another product");
            }
        } catch (IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed authorization product", exception);
        }
    }

    private JsonObject releaseBody() {
        JsonObject body = new JsonObject();
        body.addProperty("product", product());
        body.addProperty("release_id", config.releaseId());
        body.addProperty("client_version", config.clientVersion());
        body.addProperty("client_sha256", config.releaseDigest());
        body.addProperty("game_uuid", identity.uuid());
        body.addProperty("game_name", identity.username());
        return body;
    }

    private static JsonObject hardwareJson(HardwareFingerprint hardware) {
        JsonObject result = new JsonObject();
        result.addProperty("system", hardware.system());
        result.addProperty("board", hardware.board());
        result.addProperty("processor", hardware.processor());
        JsonArray monitors = new JsonArray();
        hardware.monitors().forEach(monitors::add);
        result.add("monitors", monitors);
        return result;
    }

    private byte[] decryptToken(JsonObject envelope, PrivateKey privateKey, byte[] message) throws Exception {
        byte[] ephemeralRaw = Crypto.b64(requiredString(envelope, "ephemeral_key"));
        byte[] nonce = Crypto.b64(requiredString(envelope, "nonce"));
        byte[] ciphertext = Crypto.b64(requiredString(envelope, "ciphertext"));
        JsonObject transcript = GSON.fromJson(new String(message, StandardCharsets.UTF_8), JsonObject.class);
        byte[] salt = Crypto.b64(requiredString(transcript, "nonce"));
        if (ephemeralRaw.length != 32 || nonce.length != 12 || salt.length != 32) {
            throw new GeneralSecurityException("Invalid token envelope");
        }
        KeyAgreement exchange = KeyAgreement.getInstance("X25519");
        exchange.init(privateKey);
        exchange.doPhase(Crypto.x25519Public(ephemeralRaw), true);
        byte[] sharedSecret = exchange.generateSecret(), wrappingKey = null;
        try {
            wrappingKey = Crypto.hkdf(sharedSecret, salt, TOKEN_INFO, 32);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(message);
            byte[] seed = cipher.doFinal(ciphertext);
            if (seed.length != 32) {
                Arrays.fill(seed, (byte) 0);
                throw new GeneralSecurityException("Invalid installation token length");
            }
            return seed;
        } finally {
            Arrays.fill(sharedSecret, (byte) 0);
            if (wrappingKey != null) Arrays.fill(wrappingKey, (byte) 0);
        }
    }

    private JsonObject verifyToken(String token, String audience) throws GeneralSecurityException {
        try {
            if (token == null || token.length() > AUTH_RESPONSE_LIMIT) {
                throw new GeneralSecurityException("Malformed signed grant");
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                throw new GeneralSecurityException("Malformed signed grant");
            }
            byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            if (!Crypto.verify(serverKey, signed, Crypto.b64(parts[2]))) {
                throw new GeneralSecurityException("Signed grant was not issued by this server");
            }
            JsonObject header = GSON.fromJson(new String(Crypto.b64(parts[0]), StandardCharsets.UTF_8), JsonObject.class);
            JsonObject claims = GSON.fromJson(new String(Crypto.b64(parts[1]), StandardCharsets.UTF_8), JsonObject.class);
            long now = Instant.now().getEpochSecond();
            if (!"EdDSA".equals(requiredString(header, "alg"))
                || !"CST".equals(requiredString(header, "typ"))
                || !audience.equals(requiredString(claims, "aud"))
                || requiredLong(claims, "nbf", 0) > now + 5
                || requiredLong(claims, "exp") <= now) {
                throw new GeneralSecurityException("Signed grant is not valid now");
            }
            return claims;
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException exception) {
            throw new GeneralSecurityException("Malformed signed grant", exception);
        }
    }

    private JsonObject post(String path, JsonObject body)
        throws IOException, InterruptedException, AuthRejectedException, GeneralSecurityException {
        return post(path, body, null);
    }

    private JsonObject post(String path, JsonObject body, DpopProofSigner signer)
        throws IOException, InterruptedException, AuthRejectedException, GeneralSecurityException {
        URI endpoint = sameOrigin(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (signer != null) {
            try { builder.header("DPoP", signer.proof("POST", endpoint, null)); }
            catch (GeneralSecurityException exception) { throw new IOException("Could not sign authentication request", exception); }
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response = sendAuth(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw rejected(response.statusCode(), response.body());
        }
        try {
            JsonObject parsed = GSON.fromJson(response.body(), JsonObject.class);
            if (parsed == null) {
                throw new JsonParseException("empty response");
            }
            return parsed;
        } catch (JsonParseException exception) {
            throw new GeneralSecurityException("Authentication service returned invalid data", exception);
        }
    }

    private HttpResponse<String> sendAuth(HttpRequest request)
        throws IOException, InterruptedException, GeneralSecurityException {
        try {
            var pending = transport().sendAsync(request, information -> HttpResponse.BodySubscribers.mapping(
                new BoundedBodySubscriber(information.statusCode() >= 200 && information.statusCode() < 300 ? AUTH_RESPONSE_LIMIT : 4096, -1),
                bytes -> new String(bytes, StandardCharsets.UTF_8)));
            try { return pending.get(12, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException error) { throw new IOException("Authentication response timed out", error); }
            catch (java.util.concurrent.ExecutionException error) {
                if (error.getCause() instanceof IOException io) throw io;
                throw new IOException("Authentication transport failed", error.getCause());
            } finally { pending.cancel(true); }
        } catch (IOException exception) {
            Throwable cause = exception;
            for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
                if (cause instanceof ResponseLimitException) {
                    throw new GeneralSecurityException("Authentication response exceeded its size limit", exception);
                }
            }
            throw exception;
        }
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBytes(int maximumBytes) {
        return information -> {
            if (information.statusCode() < 200 || information.statusCode() >= 300) {
                return new RejectedBodySubscriber();
            }
            long declared;
            try {
                declared = information.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (NumberFormatException exception) {
                declared = Long.MAX_VALUE;
            }
            return new BoundedBodySubscriber(maximumBytes, declared);
        };
    }

    private static final class RejectedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = CompletableFuture.completedFuture(new byte[0]);

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> buffers) { }
        @Override public void onError(Throwable error) { }
        @Override public void onComplete() { }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long maximumBytes;
        private final boolean invalidLength;
        private Flow.Subscription subscription;
        private long received;
        private boolean complete;

        BoundedBodySubscriber(int maximumBytes, long declaredLength) {
            this.maximumBytes = maximumBytes;
            this.invalidLength = declaredLength < -1 || declaredLength > maximumBytes;
        }

        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            complete = invalidLength;
            delegate.onSubscribe(new Flow.Subscription() {
                @Override public void request(long count) { if (!complete) value.request(count); }
                @Override public void cancel() { value.cancel(); }
            });
            if (invalidLength) {
                value.cancel();
                delegate.onError(new ResponseLimitException());
            }
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (complete) return;
            long batchBytes = 0;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximumBytes - received - batchBytes) {
                    complete = true;
                    subscription.cancel();
                    delegate.onError(new ResponseLimitException());
                    return;
                }
                batchBytes += buffer.remaining();
            }
            received += batchBytes;
            delegate.onNext(buffers);
        }

        @Override public void onError(Throwable error) {
            if (complete) return;
            complete = true;
            delegate.onError(error);
        }

        @Override public void onComplete() {
            if (complete) return;
            complete = true;
            delegate.onComplete();
        }
    }

    private static final class ResponseLimitException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseLimitException() { super("Response body exceeded its size limit"); }
    }

    private URI sameOrigin(String path) throws IOException {
        URI endpoint = config.baseUri().resolve(path);
        URI base = config.baseUri();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
            || !base.getHost().equalsIgnoreCase(endpoint.getHost())
            || effectivePort(base) != effectivePort(endpoint)) {
            throw new IOException("Authentication response referenced an untrusted endpoint");
        }
        return endpoint;
    }

    synchronized DpopProofSigner bindCredential(InstallCredential credential) throws GeneralSecurityException {
        if (closed || credential.isDestroyed()) throw new GeneralSecurityException("Installation session has been closed");
        JsonObject certificate = verifyToken(credential.certificate(), "celeste-installation");
        if (!credential.deviceId().equals(requiredString(certificate, "device"))
            || !Long.toString(credential.uid()).equals(requiredString(certificate, "sub"))) {
            throw new GeneralSecurityException("Installation certificate does not match this device");
        }
        if (boundCredential == credential && proofSigner != null && !proofSigner.isDestroyed()) return proofSigner;
        DpopProofSigner signer = credential.signer(Crypto.b64(requiredString(certificate, "install_key")));
        try {
            // Existing signed activation certificates can predate DPoP; new access grants cannot.
            if (certificate.has("cnf")) signer.requireBinding(certificate);
            if (proofSigner != null) proofSigner.close();
            proofSigner = signer;
            boundCredential = credential;
            return signer;
        } catch (GeneralSecurityException | RuntimeException | Error failure) { signer.close(); throw failure; }
    }

    synchronized void releaseCredential(InstallCredential credential) {
        if (boundCredential == credential) {
            if (proofSigner != null) proofSigner.close();
            proofSigner = null; boundCredential = null;
        }
    }

    @Override public void close() {
        final DpopProofSigner retiredSigner;
        final IncidentTransport[] reporters;
        synchronized (this) {
            if (closed) return;
            closed = true;
            reporters = incidentTransports.toArray(IncidentTransport[]::new);
            retiredSigner = proofSigner;
            proofSigner = null; boundCredential = null;
        }
        // Cancellation can run controller continuations inline. Never invoke those
        // while holding the service monitor used by session/context lookups.
        for (IncidentTransport reporter : reporters) reporter.close();
        if (retiredSigner != null) retiredSigner.close();
        if (transportOwner != null) transportOwner.close();
    }

    private String requestProof(String method, URI endpoint, String token) throws IOException {
        DpopProofSigner signer = proofSigner;
        if (signer == null) throw new IOException("Authenticate this installation before requesting protected content");
        try { return signer.proof(method, endpoint, token); }
        catch (GeneralSecurityException exception) { throw new IOException("Could not sign protected request", exception); }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 443;
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return value.getAsString();
    }

    private static long requiredLong(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("Invalid integer field: " + name, exception);
        }
    }

    private static long requiredLong(JsonObject object, String name, long fallback) {
        return object != null && !object.has(name) ? fallback : requiredLong(object, name);
    }

    private static byte[] requiredB64(JsonObject object, String name) {
        return Crypto.b64(requiredString(object, name));
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("Missing object: " + name);
        }
        return value.getAsJsonObject();
    }

    private static String requiredHex(JsonObject object, String name) {
        String value = requiredString(object, name).toLowerCase(java.util.Locale.ROOT);
        if (value.length() != 64 || !value.chars().allMatch(character ->
            character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalStateException("Invalid SHA-256 field: " + name);
        }
        return value;
    }

    private AuthRejectedException rejected(int status, String response) {
        String code = "", request = ""; int retry = 0;
        try {
            JsonObject value = GSON.fromJson(response, JsonObject.class);
            code = requiredString(value, "error_code");
            if (!code.matches("[a-z_]{1,64}")) code = "";
            if (value.has("request_id")) { request = requiredString(value, "request_id"); if (!request.matches("[A-Za-z0-9_-]{1,80}")) request = ""; }
            if (value.has("retry_after_seconds")) retry = (int)Math.min(86400, Math.max(0, requiredLong(value, "retry_after_seconds")));
        } catch (RuntimeException ignored) { code = ""; }
        String message = switch (code) {
            case "device_mismatch" -> "This license is linked to another device";
            case "subscription_inactive" -> "Your subscription is inactive";
            case "token_revoked", "reactivation_required" -> "Sign in again to reactivate this installation";
            case "token_rotated", "invalid_grant" -> "Authorization changed; sign in again";
            case "concurrent_session_rejected" -> "Verify this device again before reconnecting";
            case "rebind_cooldown" -> "Wait before changing the linked device";
            case "runtime_disabled" -> "This release is unavailable; update the launcher";
            default -> switch (status) {
                case 401, 403 -> "License or device verification failed";
                case 409 -> expectedBetaCatalog == null ? "This license needs its device binding reset" : "Refresh the beta release before trying again";
                case 429 -> "Too many attempts; wait before retrying";
                default -> "Authentication service returned " + status;
            };
        };
        return new AuthRejectedException(status, message, code, request, retry);
    }
    static final class AuthRejectedException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int statusCode;
        private final String code, requestId;
        private final int retryAfterSeconds;
        AuthRejectedException(int statusCode, String message) { this(statusCode, message, "", "", 0); }
        AuthRejectedException(int statusCode, String message, String code, String requestId, int retryAfterSeconds) {
            super(message); this.statusCode = statusCode; this.code = code; this.requestId = requestId; this.retryAfterSeconds = retryAfterSeconds;
        }
        int statusCode() { return statusCode; }
        String code() { return code; }
        String requestId() { return requestId; }
        int retryAfterSeconds() { return retryAfterSeconds; }
    }
}
