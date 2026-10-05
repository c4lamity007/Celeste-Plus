package dev.c4lam.celeste.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.ByteBuffer;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** Signed, nonce-bound pin rotation; credential-bearing requests use PKIX AND exact leaf pins. */
public final class PinnedTls {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ConcurrentHashMap<String, Entry> CLIENTS = new ConcurrentHashMap<>();
    private static final Set<String> CLAIMS = Set.of("aud", "product", "origin", "request_nonce", "iat", "nbf", "exp", "leaf_sha256");
    private static final byte[] ED25519 = HexFormat.of().parseHex("302a300506032b6570032100");
    private PinnedTls() { }
    private record Client(HttpClient http, long deadline) { }
    record Policy(Set<String> pins, long expires) { }

    /** An auth session or delivery client owns its transport until explicit retirement. */
    public static Owner acquire(URI origin, byte[] serverKey, String product) {
        final String base;
        try { base = origin(origin); }
        catch (IOException invalid) { throw new IllegalArgumentException("Invalid TLS origin", invalid); }
        if (serverKey == null || serverKey.length != 32 || !("celeste_plus".equals(product) || "celeste".equals(product)))
            throw new IllegalArgumentException("Invalid TLS policy identity");
        String key = base + ":" + product + ":" + HexFormat.of().formatHex(serverKey);
        Entry entry = CLIENTS.compute(key, (ignored, existing) -> {
            if (existing == null) return new Entry(base, serverKey, product);
            existing.owners++;
            return existing;
        });
        return new Owner(key, entry);
    }

    public static final class Owner implements AutoCloseable {
        private final String key;
        private final Entry entry;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Owner(String key, Entry entry) { this.key = key; this.entry = entry; }
        public HttpClient client() throws IOException, InterruptedException {
            if (closed.get()) throw new IOException("TLS transport owner is closed");
            HttpClient result = PinnedTls.client(entry);
            if (closed.get()) throw new IOException("TLS transport owner is closed");
            return result;
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            boolean[] last = {false};
            CLIENTS.computeIfPresent(key, (ignored, existing) -> {
                if (existing != entry) return existing;
                if (--existing.owners != 0) return existing;
                last[0] = true;
                return null;
            });
            if (last[0]) entry.retire();
        }
    }

    private static final class Entry {
        private final String base, product;
        private final byte[] serverKey;
        // Policy refresh may wait on HTTPS while retirement only needs this short lock.
        private final Object publication = new Object();
        private final Set<HttpClient> transports = new HashSet<>();
        private volatile Client current;
        private volatile boolean retired;
        private HttpClient bootstrap;
        private CompletableFuture<?> pending;
        private int owners = 1; // Only changed inside CLIENTS.compute.
        private Entry(String base, byte[] serverKey, String product) {
            this.base = base; this.serverKey = serverKey.clone(); this.product = product;
        }
        private void retire() {
            List<HttpClient> closing;
            CompletableFuture<?> operation;
            synchronized (publication) {
                retired = true; current = null;
                closing = new java.util.ArrayList<>(transports); transports.clear();
                if (bootstrap != null) closing.add(bootstrap);
                bootstrap = null; operation = pending; pending = null;
            }
            if (operation != null) operation.cancel(true);
            closing.forEach(HttpClient::shutdownNow);
        }
    }

    private static HttpClient client(Entry entry) throws IOException, InterruptedException {
        synchronized (entry) { return refresh(entry); }
    }

    private static HttpClient refresh(Entry entry) throws IOException, InterruptedException {
        if (entry.retired) throw new IOException("TLS transport owner is closed");
        String base = entry.base, product = entry.product;
        byte[] serverKey = entry.serverKey;
        Client existing = entry.current;
        if (existing != null && System.nanoTime() - existing.deadline < 0) return existing.http;
        // The only unpinned HTTPS request carries no token, identity, proof or license.
        // PKIX remains mandatory and its response is useless without the embedded-key signature.
        byte[] entropy = new byte[24]; RANDOM.nextBytes(entropy);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); Arrays.fill(entropy, (byte) 0);
        URI endpoint = URI.create(base + "/v1/transport-policy?product=" + product);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(12))
            .header("X-Celeste-Request-Nonce", nonce).header("Accept", "application/json").GET().build();
        HttpClient bootstrap = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        byte[] bytes;
        PolicyBody body = new PolicyBody();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            synchronized (entry.publication) {
                if (entry.retired) throw new IOException("TLS transport owner is closed");
                entry.bootstrap = bootstrap;
            }
            pending = bootstrap.sendAsync(request, info -> body);
            synchronized (entry.publication) {
                if (entry.retired) throw new IOException("TLS transport owner is closed");
                entry.pending = pending;
            }
            HttpResponse<byte[]> response = pending.get(12, TimeUnit.SECONDS);
            bytes = response.body();
            if (response.statusCode() != 200 || !response.uri().equals(endpoint) || bytes.length > 16_384)
                throw new IOException("TLS pin policy unavailable");
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("TLS pin policy unavailable", error);
        } finally {
            body.cancel(); if (pending != null) pending.cancel(true); bootstrap.shutdownNow();
            synchronized (entry.publication) { entry.bootstrap = null; entry.pending = null; }
        }
        try {
            JsonObject wrapper = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!wrapper.keySet().equals(Set.of("policy"))) throw new GeneralSecurityException("Invalid TLS policy wrapper");
            Policy policy = verify(wrapper.get("policy").getAsString(), serverKey, base, product, nonce, Instant.now().getEpochSecond());
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509ExtendedTrustManager delegate = Arrays.stream(factory.getTrustManagers())
                .filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast).findFirst()
                .orElseThrow(() -> new GeneralSecurityException("System TLS validation is unavailable"));
            long remaining = Math.max(0, policy.expires - Instant.now().getEpochSecond() - 2);
            if (remaining == 0) throw new GeneralSecurityException("TLS pin policy expired");
            long deadline = System.nanoTime() + Duration.ofSeconds(remaining).toNanos();
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new LeafTrust(delegate, policy.pins, deadline)}, null);
            SSLParameters parameters = new SSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            HttpClient pinned = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).sslContext(context).sslParameters(parameters).build();
            Client previous;
            synchronized (entry.publication) {
                if (entry.retired) { pinned.shutdownNow(); throw new IOException("TLS transport owner is closed"); }
                previous = entry.current;
                entry.transports.removeIf(HttpClient::isTerminated);
                entry.transports.add(pinned);
                entry.current = new Client(pinned, deadline);
            }
            if (previous != null) previous.http.shutdown();
            return pinned;
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new IOException("TLS pin policy verification failed", error);
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static final class PolicyBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> value = new CompletableFuture<>();
        private final byte[] buffer = new byte[16_384];
        private int size;
        private Flow.Subscription subscription;
        private boolean cancelled;
        @Override public CompletionStage<byte[]> getBody() { return value; }
        @Override public synchronized void onSubscribe(Flow.Subscription next) { if (subscription != null || cancelled) next.cancel(); else { subscription = next; next.request(1); } }
        @Override public synchronized void onNext(List<ByteBuffer> chunks) {
            if (cancelled || value.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > buffer.length - size) { onError(new IOException("TLS policy exceeds bounds")); return; }
                int count = chunk.remaining(); chunk.get(buffer, size, count); size += count;
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) { value.completeExceptionally(error); cancel(); }
        @Override public synchronized void onComplete() { value.complete(Arrays.copyOf(buffer, size)); Arrays.fill(buffer, (byte) 0); }
        synchronized void cancel() { cancelled = true; if (subscription != null) subscription.cancel(); Arrays.fill(buffer, (byte) 0); }
    }

    static Policy verify(String token, byte[] key, String origin, String product, String nonce, long now)
        throws GeneralSecurityException {
        try {
            if (key.length != 32 || token == null || token.length() > 16_384 || !nonce.matches("[A-Za-z0-9_-]{32}"))
                throw new GeneralSecurityException("Invalid pin policy input");
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) throw new GeneralSecurityException("Invalid pin policy token");
            byte[] publicKey = Arrays.copyOf(ED25519, ED25519.length + key.length);
            System.arraycopy(key, 0, publicKey, ED25519.length, key.length);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(decode(parts[2]))) throw new GeneralSecurityException("Invalid pin policy signature");
            JsonObject header = JsonParser.parseString(new String(decode(parts[0]), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject claims = JsonParser.parseString(new String(decode(parts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!header.keySet().equals(Set.of("alg", "kid", "typ")) || !text(header, "kid").equals("celeste-release-1")
                || !text(header, "alg").equals("EdDSA") || !text(header, "typ").equals("CST")
                || !claims.keySet().equals(CLAIMS) || !text(claims, "aud").equals("celeste-transport-policy")
                || !text(claims, "product").equals(product) || !text(claims, "origin").equals(origin)
                || !text(claims, "request_nonce").equals(nonce)) throw new GeneralSecurityException("Pin policy binding differs");
            long issued = integer(claims, "iat"), expires = integer(claims, "exp");
            if (issued > now + 5 || integer(claims, "nbf") != issued || expires <= now || expires <= issued || expires - issued > 120)
                throw new GeneralSecurityException("Pin policy is not current");
            Set<String> pins = new HashSet<>();
            var values = claims.getAsJsonArray("leaf_sha256");
            if (values == null || values.isEmpty() || values.size() > 4) throw new GeneralSecurityException("Invalid pin count");
            for (var value : values) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || !value.getAsString().matches("[0-9a-f]{64}") || !pins.add(value.getAsString()))
                    throw new GeneralSecurityException("Invalid or repeated leaf pin");
            }
            return new Policy(Set.copyOf(pins), expires);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException error) {
            throw new GeneralSecurityException("Malformed pin policy", error);
        }
    }
    private static byte[] decode(String value) throws GeneralSecurityException {
        if (!value.matches("[A-Za-z0-9_-]+")) throw new GeneralSecurityException("Invalid policy encoding");
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) throw new GeneralSecurityException("Noncanonical policy encoding");
        return decoded;
    }
    private static String text(JsonObject value, String key) throws GeneralSecurityException {
        var item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw new GeneralSecurityException("Invalid policy string");
        return item.getAsString();
    }
    private static long integer(JsonObject value, String key) throws GeneralSecurityException {
        var item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() || !item.toString().matches("[0-9]{1,12}"))
            throw new GeneralSecurityException("Invalid policy time");
        return item.getAsLong();
    }
    private static String origin(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IOException("Invalid pinned origin");
        return "https://" + uri.getRawAuthority();
    }
    static final class LeafTrust extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final Set<String> pins;
        private final long deadline;
        LeafTrust(X509ExtendedTrustManager delegate, Set<String> pins) { this(delegate,pins,System.nanoTime()+Duration.ofSeconds(120).toNanos()); }
        LeafTrust(X509ExtendedTrustManager delegate, Set<String> pins, long deadline) { this.delegate = delegate; this.pins = Set.copyOf(pins); this.deadline=deadline; }
        private void pin(X509Certificate[] chain) throws CertificateException {
            try {
                if (System.nanoTime() - deadline >= 0) throw new CertificateException("TLS pin policy expired before handshake");
                if (chain == null || chain.length == 0 || !pins.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))))
                    throw new CertificateException("TLS server leaf certificate is not authorized");
            } catch (GeneralSecurityException error) { throw new CertificateException("TLS server leaf certificate rejected", error); }
        }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkServerTrusted(c, a); pin(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { delegate.checkServerTrusted(c, a, s); pin(c); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkServerTrusted(c, a, e); pin(c); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkClientTrusted(c, a); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { delegate.checkClientTrusted(c, a, s); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkClientTrusted(c, a, e); }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
    }
}
