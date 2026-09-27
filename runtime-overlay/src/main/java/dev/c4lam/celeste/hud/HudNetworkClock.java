package dev.c4lam.celeste.hud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Optional display clock: public HTTPS Date only, never an OS wall-clock fallback. */
public final class HudNetworkClock implements AutoCloseable {
    private static final String UNKNOWN = "--:--";
    private static final String TRUST = "/META-INF/celeste-beta/bootstrap-trust.json";
    private static final long REFRESH_SECONDS = 300L;
    private static final long MAX_AGE_NANOS = TimeUnit.MINUTES.toNanos(15L);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private final Supplier<URI> endpoint;
    private final Supplier<Transport> transportFactory;
    private final LongSupplier monotonic;
    private final Supplier<ZoneId> zone;
    private volatile Sample sample;
    private volatile boolean enabled;
    private boolean closed;
    private boolean getOnly;
    private int failures;
    private long generation;
    private ScheduledThreadPoolExecutor worker;
    private ScheduledFuture<?> pending;
    private Transport transport;

    public HudNetworkClock() {
        this(HudNetworkClock::packagedEndpoint, HttpsTransport::new, System::nanoTime, ZoneId::systemDefault);
    }

    HudNetworkClock(Supplier<URI> endpoint, Supplier<Transport> transportFactory,
                    LongSupplier monotonic, Supplier<ZoneId> zone) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.transportFactory = Objects.requireNonNull(transportFactory);
        this.monotonic = Objects.requireNonNull(monotonic);
        this.zone = Objects.requireNonNull(zone);
    }

    /** Idempotent per-frame integration is safe; only transitions schedule/retire work. */
    public synchronized void setEnabled(boolean value) {
        if (closed || enabled == value) return;
        enabled = value;
        generation++;
        sample = null;
        failures = 0;
        if (!value) {
            if (pending != null) pending.cancel(true);
            if (transport != null) transport.close();
            if (worker != null) worker.shutdownNow();
            pending = null;
            transport = null;
            worker = null;
            return;
        }
        worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "Celeste HUD HTTPS Clock");
            thread.setDaemon(true);
            thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        long token = generation;
        pending = worker.schedule(() -> refresh(token), 0L, TimeUnit.SECONDS);
    }

    /** No I/O, network scheduling, or wall-clock reads on the render thread. */
    public String text() {
        Sample current = sample;
        if (!enabled || current == null) return UNKNOWN;
        long elapsed = monotonic.getAsLong() - current.atNanos();
        if (elapsed < 0L || elapsed > MAX_AGE_NANOS) return UNKNOWN;
        return DISPLAY.withZone(zone.get()).format(current.instant().plusNanos(elapsed));
    }

    @Override public synchronized void close() {
        if (closed) return;
        setEnabled(false);
        closed = true;
    }

    private synchronized boolean current(long token) {
        return enabled && !closed && generation == token;
    }

    private void refresh(long token) {
        boolean success = false;
        try {
            URI uri = healthEndpoint(endpoint.get());
            Transport active;
            boolean useGet;
            synchronized (this) {
                if (!current(token)) return;
                if (transport == null) transport = transportFactory.get();
                active = transport;
                useGet = getOnly;
            }
            long sent = monotonic.getAsLong();
            Response response = active.fetch(uri, useGet ? "GET" : "HEAD");
            if (!useGet && (response.status() == 405 || response.status() == 501)) {
                synchronized (this) {
                    if (!current(token)) return;
                    getOnly = true; // Confirmed unsupported HEAD; use this same public health endpoint.
                }
                sent = monotonic.getAsLong();
                response = active.fetch(uri, "GET");
            }
            long received = monotonic.getAsLong();
            Instant date = responseDate(response);
            long trip = received - sent;
            if (trip < 0L || trip > TimeUnit.SECONDS.toNanos(6L)) throw new IOException("Clock response too old");
            synchronized (this) {
                if (!current(token)) return;
                // HTTP Date has one-second precision; midpoint compensation is only an estimate.
                sample = new Sample(date.plusNanos(trip / 2L), received);
                success = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unavailable) {
            // Optional HUD information must never fail authentication or fall back to the OS clock.
        } finally {
            synchronized (this) {
                if (current(token)) {
                    failures = success ? 0 : Math.min(4, failures + 1);
                    long delay = success ? REFRESH_SECONDS : retrySeconds(failures);
                    pending = worker.schedule(() -> refresh(token), delay, TimeUnit.SECONDS);
                }
            }
        }
    }

    static long retrySeconds(int failures) {
        return Math.min(REFRESH_SECONDS, 60L << Math.max(0, Math.min(3, failures - 1)));
    }

    static Instant responseDate(Response response) throws IOException {
        if (response.status() < 200 || response.status() >= 300 || response.dates().size() != 1) {
            throw new IOException("No successful unique HTTPS Date");
        }
        String date = response.dates().getFirst();
        if (date.length() > 64 || !date.endsWith(" GMT")) throw new IOException("Invalid HTTP Date");
        try {
            return ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid HTTP Date", invalid);
        }
    }

    static URI healthEndpoint(URI origin) {
        if (origin == null || !"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
            || origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getFragment() != null
            || (origin.getPort() != -1 && origin.getPort() != 443)
            || !(origin.getPath().isEmpty() || origin.getPath().equals("/") || origin.getPath().equals("/healthz"))) {
            throw new IllegalArgumentException("Clock requires the packaged public HTTPS origin");
        }
        return origin.resolve("/healthz");
    }

    private static URI packagedEndpoint() {
        // The two-part memory loader owns META-INF resources; this is public packaging metadata,
        // not a credential or an alternate auth parser. Unconfigured developer builds stay blank.
        try (InputStream input = HudNetworkClock.class.getResourceAsStream(TRUST)) {
            if (input == null) throw new IOException("No packaged public origin");
            byte[] data = input.readNBytes(4097);
            if (data.length > 4096) throw new IOException("Public trust metadata too large");
            JsonObject trust = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!"celeste-plus-bootstrap-trust-1".equals(trust.get("schema").getAsString())
                || !"celeste_plus".equals(trust.get("product").getAsString())) {
                throw new IOException("Unrecognized public trust metadata");
            }
            return healthEndpoint(URI.create(trust.get("origin").getAsString()));
        } catch (IOException invalid) {
            throw new IllegalStateException("Clock public origin unavailable", invalid);
        }
    }

    record Sample(Instant instant, long atNanos) { }
    record Response(int status, List<String> dates) { }
    interface Transport extends AutoCloseable {
        Response fetch(URI uri, String method) throws Exception;
        @Override void close();
    }

    /** Independent HTTPS client: no auth headers, cookies, authenticator, redirects, or subprocesses. */
    private static final class HttpsTransport implements Transport {
        private HttpClient client;
        private boolean closed;

        private HttpClient client() throws IOException {
            synchronized (this) {
                if (closed) throw new IOException("Clock transport stopped");
                if (client != null) return client;
            }
            // TLS/client initialization stays on the worker, outside the lifecycle lock.
            HttpClient created = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3L))
                .followRedirects(HttpClient.Redirect.NEVER).build();
            synchronized (this) {
                if (!closed) { client = created; return created; }
            }
            created.shutdownNow();
            throw new IOException("Clock transport stopped");
        }

        @Override public Response fetch(URI uri, String method) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(healthEndpoint(uri)).timeout(Duration.ofSeconds(4L))
                .header("Accept", "application/json").header("Cache-Control", "no-cache")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
            CompletableFuture<HttpResponse<InputStream>> future = client().sendAsync(request,
                HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = null;
            try {
                response = future.get(5L, TimeUnit.SECONDS);
                // We only need headers. Closing immediately discards/cancels the body, with no parsing.
                return new Response(response.statusCode(), response.headers().allValues("Date"));
            } finally {
                if (response != null) response.body().close();
                else {
                    // Close a headers/body stream that won a race with timeout or interruption.
                    future.thenAccept(late -> {
                        try { late.body().close(); } catch (IOException ignored) { }
                    });
                    future.cancel(true);
                }
            }
        }

        @Override public void close() {
            HttpClient retired;
            synchronized (this) { closed = true; retired = client; client = null; }
            if (retired != null) retired.shutdownNow();
        }
    }
}
