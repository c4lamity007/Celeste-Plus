package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.license.LicenseIdentity;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

public final class AuthBootstrap {
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cst-license-gateway");
        thread.setDaemon(true);
        return thread;
    });
    private static CredentialStore STORE = new CredentialStore();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final AtomicBoolean AUTHORIZED = new AtomicBoolean();
    private static final AtomicBoolean LOCKED = new AtomicBoolean();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean STOPPED = new AtomicBoolean();
    private static volatile String status = "Waiting for license";
    private static volatile AuthConfig config;
    private static volatile AuthServiceClient service;
    private static volatile String expectedBetaCatalog;
    private static volatile HardwareFingerprint hardware;
    private static volatile InstallCredential sessionCredential;
    private static volatile String sessionGrant;
    private static volatile long lastHeartbeatSuccessNanos;
    private static Runnable authorizedInitializer;
    private static Runnable authorizedShutdown;
    private static volatile Consumer<Throwable> lateFailureObserver;
    private static volatile boolean lateInstallation;
    private static CompletableFuture<Void> lateStopCompletion;

    private AuthBootstrap() {
    }

    public static void install(Runnable initializer, Runnable shutdown) {
        install(initializer, shutdown, false);
    }

    /** Same authentication as normal startup, for a host whose CLIENT_STARTED event already fired. */
    public static void installAfterClientStarted(Runnable initializer, Runnable shutdown) {
        install(initializer, shutdown, true);
    }

    /** Same required authentication, with failure observation for the late runtime host. */
    public static void installAfterClientStarted(Runnable initializer, Runnable shutdown, Consumer<Throwable> failureObserver) {
        install(initializer, shutdown, true, Objects.requireNonNull(failureObserver, "failureObserver"));
    }

    /** Request late-runtime authorization shutdown and observe actual worker termination. */
    public static synchronized CompletionStage<Void> stopAfterClientStarted() {
        if (!lateInstallation) return CompletableFuture.<Void>completedFuture(null).minimalCompletionStage();
        stop(MinecraftClient.getInstance());
        if (lateStopCompletion == null) {
            lateStopCompletion = CompletableFuture.runAsync(() -> {
                try {
                    if (!WORKER.awaitTermination(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Authorization worker did not finish stopping within 30 seconds");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Authorization shutdown observation was interrupted", failure);
                }
            });
        }
        return lateStopCompletion.minimalCompletionStage();
    }

    /** Called by the beta host after verifying the server-signed artifact catalog, before installation. */
    public static synchronized void configureBetaRuntime(String catalogSha256) {
        if (INSTALLED.get() || expectedBetaCatalog != null) {
            throw new IllegalStateException("Beta runtime context must be selected once before installation");
        }
        if (catalogSha256 == null || !catalogSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Verified beta catalog hash is invalid");
        }
        STORE = new CredentialStore("celeste_plus");
        expectedBetaCatalog = catalogSha256;
    }

    private static synchronized void install(Runnable initializer, Runnable shutdown, boolean alreadyStarted) {
        install(initializer, shutdown, alreadyStarted, null);
    }

    private static synchronized void install(Runnable initializer, Runnable shutdown, boolean alreadyStarted,
                                             Consumer<Throwable> failureObserver) {
        Objects.requireNonNull(initializer, "initializer");
        Objects.requireNonNull(shutdown, "shutdown");
        if (!INSTALLED.compareAndSet(false, true)) {
            throw new IllegalStateException("Client authorization is already installed");
        }
        authorizedInitializer = initializer;
        authorizedShutdown = shutdown;
        lateInstallation = alreadyStarted;
        lateFailureObserver = failureObserver;
        if (alreadyStarted) {
            BetaSubscriptions.register(ClientLifecycleEvents.CLIENT_STOPPING, "celeste:auth/stopping",
                ClientLifecycleEvents.ClientStopping.class, AuthBootstrap::stop, ClientLifecycleEvents.CLIENT_STOPPING::register);
        } else {
            ClientLifecycleEvents.CLIENT_STOPPING.register(AuthBootstrap::stop);
        }
        WORKER.scheduleWithFixedDelay(AuthBootstrap::heartbeat, 30, 30, TimeUnit.SECONDS);
        if (alreadyStarted) {
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> start(client));
        } else {
            ClientLifecycleEvents.CLIENT_STARTED.register(AuthBootstrap::start);
        }
    }

    public static String status() {
        return status;
    }

    public static boolean busy() {
        return BUSY.get();
    }

    public static boolean locked() {
        return LOCKED.get();
    }

    public static boolean authorized() {
        return AUTHORIZED.get() && !LOCKED.get() && !STOPPED.get();
    }

    public static boolean hasLocalCredential() {
        return STORE.exists();
    }

    public record CloudResponse(String contentType, byte[] body) {
    }

    public static CloudResponse cloudRequest(String method, String path, String contentType, byte[] body) throws Exception {
        AuthServiceClient currentService = service;
        String currentGrant = sessionGrant;
        if (!authorized() || currentService == null || currentGrant == null || currentGrant.isBlank()) {
            throw new IOException("Cloud configs require an active license session");
        }
        AuthServiceClient.CloudResponse response = currentService.cloudRequest(
            method,
            path,
            contentType,
            body,
            currentGrant
        );
        return new CloudResponse(response.contentType(), response.body());
    }

    public static void submitLicense(String licenseKey) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (licenseKey == null || licenseKey.strip().length() < 20 || !BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Binding this installation";
        WORKER.execute(() -> {
            try {
                ensureReady();
                AuthServiceClient.Enrollment enrollment = service.enroll(licenseKey, hardware);
                STORE.save(enrollment.credential());
                AuthServiceClient.Authenticated authenticated = service.authenticate(enrollment.credential(), hardware);
                authorize(client, authenticated, enrollment.credential());
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception), exception);
            } finally {
                BUSY.set(false);
            }
        });
    }

    public static void retry() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (LOCKED.get()) {
            show(client, "Restart Minecraft before authorizing again");
            return;
        }
        if (!STORE.exists()) {
            show(client, "Enter a license to bind this installation");
            return;
        }
        authenticateStored(client);
    }

    public static void clearLocalBinding() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (BUSY.get() || LOCKED.get()) {
            return;
        }
        try {
            STORE.clear();
            show(client, "Local binding cleared; enter a license");
        } catch (IOException exception) {
            show(client, "Could not clear the local binding");
        }
    }

    private static void start(MinecraftClient client) {
        if (STOPPED.get() || !STARTED.compareAndSet(false, true)) {
            return;
        }
        if (STORE.exists()) {
            authenticateStored(client);
        } else {
            show(client, "Enter a license to bind this installation");
            WORKER.execute(() -> {
                try {
                    ensureReady();
                } catch (Exception exception) {
                    fail(client, friendlyMessage(exception), exception);
                }
            });
        }
    }

    private static void authenticateStored(MinecraftClient client) {
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        status = "Verifying this installation";
        show(client, status);
        WORKER.execute(() -> {
            try {
                ensureReady();
                InstallCredential credential = STORE.load();
                AuthServiceClient.Authenticated authenticated = service.authenticate(credential, hardware);
                authorize(client, authenticated, credential);
            } catch (Exception exception) {
                fail(client, friendlyMessage(exception), exception);
            } finally {
                BUSY.set(false);
            }
        });
    }

    private static synchronized void ensureReady() throws Exception {
        if (service != null && hardware != null) {
            return;
        }
        AuthConfig loaded = AuthConfig.load();
        if (expectedBetaCatalog != null && !ArtifactIntegrity.hasRetainedMemorySource()) {
            throw new SecurityException("Beta initialization requires its retained verified payload");
        }
        String observedDigest = ArtifactIntegrity.digest();
        if (!loaded.releaseDigest().equals(observedDigest)) {
            reportStartupIntegrityFailure(loaded, observedDigest);
            throw new SecurityException("Client integrity verification failed");
        }
        HardwareFingerprint observedHardware = HardwareFingerprint.collect();
        config = loaded;
        hardware = observedHardware;
        service = new AuthServiceClient(loaded, GameIdentity.collect(), expectedBetaCatalog);
    }

    private static void reportStartupIntegrityFailure(AuthConfig loaded, String observedDigest) {
        if (!STORE.exists()) {
            return;
        }
        try {
            AuthServiceClient reporter = new AuthServiceClient(loaded, GameIdentity.collect(), expectedBetaCatalog);
            reporter.checkSession(STORE.load(), observedDigest, true);
        } catch (Exception ignored) {
            // The local client still fails closed even if the signed report cannot be delivered.
        }
    }

    private static void authorize(
        MinecraftClient client,
        AuthServiceClient.Authenticated authenticated,
        InstallCredential credential
    ) {
        client.execute(() -> {
            // A response queued before shutdown/revocation must never reactivate the client.
            if (STOPPED.get() || LOCKED.get() || !AUTHORIZED.compareAndSet(false, true)) {
                return;
            }
            sessionCredential = credential;
            sessionGrant = authenticated.grant();
            lastHeartbeatSuccessNanos = System.nanoTime();
            try {
                LicenseIdentity.accept(authenticated.uid());
                authorizedInitializer.run();
                if (authorized()) {
                    status = "Authorized";
                }
            } catch (RuntimeException | LinkageError exception) {
                lockSession("Client initialization failed; restart Minecraft before trying again", exception);
            }
        });
    }

    private static void heartbeat() {
        if (!authorized()) {
            return;
        }
        AuthServiceClient currentService = service;
        InstallCredential credential = sessionCredential;
        AuthConfig currentConfig = config;
        if (currentService == null || credential == null || currentConfig == null) {
            lockSession("Session verification failed");
            return;
        }
        try {
            String observedDigest = ArtifactIntegrity.digest();
            if (!currentConfig.releaseDigest().equals(observedDigest)) {
                try {
                    currentService.checkSession(credential, observedDigest, true);
                } finally {
                    lockSession("Client integrity verification failed");
                }
                return;
            }
            String refreshedGrant = currentService.checkSession(credential, observedDigest, false);
            if (authorized()) {
                sessionGrant = refreshedGrant;
                lastHeartbeatSuccessNanos = System.nanoTime();
            }
        } catch (AuthServiceClient.AuthRejectedException exception) {
            if (exception.statusCode() == 401 || exception.statusCode() == 403 || exception.statusCode() == 409) {
                lockSession("License revoked, expired, or no longer authorized");
            } else {
                lockAfterHeartbeatGrace();
            }
        } catch (SecurityException | GeneralSecurityException exception) {
            lockSession(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            lockAfterHeartbeatGrace();
        }
    }

    private static void lockAfterHeartbeatGrace() {
        long elapsed = System.nanoTime() - lastHeartbeatSuccessNanos;
        if (elapsed >= TimeUnit.SECONDS.toNanos(95)) {
            lockSession("Authorization service could not revalidate this session");
        }
    }

    private static void lockSession(String message) {
        lockSession(message, new SecurityException(message));
    }

    private static void lockSession(String message, Throwable failure) {
        if (!AUTHORIZED.compareAndSet(true, false)) {
            return;
        }
        LOCKED.set(true);
        sessionCredential = null;
        sessionGrant = null;
        status = message;
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                Consumer<Throwable> observer = lateFailureObserver;
                if (observer != null) observer.accept(failure);
            } finally {
                try { authorizedShutdown.run(); }
                finally { LicenseIdentity.clear(); }
            }
        });
    }

    private static void stop(MinecraftClient client) {
        if (!STOPPED.compareAndSet(false, true)) {
            return;
        }
        LOCKED.set(true);
        AUTHORIZED.set(false);
        sessionCredential = null;
        sessionGrant = null;
        WORKER.shutdownNow();
        client.execute(() -> {
            try {
                authorizedShutdown.run();
            } finally {
                LicenseIdentity.clear();
            }
        });
    }

    private static void fail(MinecraftClient client, String message) {
        fail(client, message, new SecurityException(message));
    }

    private static void fail(MinecraftClient client, String message, Throwable failure) {
        status = message;
        show(client, message);
        Consumer<Throwable> observer = lateFailureObserver;
        if (observer != null && !STOPPED.get()) {
            client.execute(() -> { if (!STOPPED.get()) observer.accept(failure); });
        }
    }

    private static void show(MinecraftClient client, String message) {
        status = message;
    }

    private static String friendlyMessage(Exception exception) {
        if (exception instanceof AuthServiceClient.AuthRejectedException) {
            return exception.getMessage();
        }
        if (exception instanceof SecurityException) {
            return exception.getMessage();
        }
        if (exception instanceof IOException) {
            return "Authentication service is unavailable";
        }
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "Authentication was interrupted";
        }
        return "This installation could not be verified";
    }
}
