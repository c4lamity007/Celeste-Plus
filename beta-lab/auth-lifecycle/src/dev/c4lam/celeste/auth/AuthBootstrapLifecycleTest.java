package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.license.LicenseIdentity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

/** Each invocation is a fresh JVM exercising the unmodified production class. */
public final class AuthBootstrapLifecycleTest {
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();
    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();
    private static final AtomicInteger CLEANUPS = new AtomicInteger();

    private AuthBootstrapLifecycleTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one scenario");
        try {
            switch (args[0]) {
                case "startup-deferred" -> startupDeferred();
                case "late-start-once" -> lateStartOnce();
                case "late-failure-observer" -> lateFailureObserver();
                case "late-scoped-stop" -> lateScopedStop();
                case "duplicate-owner" -> duplicateOwner();
                case "initializer-runtime-failure" -> initializerFailure(false);
                case "initializer-linkage-failure" -> initializerFailure(true);
                case "initializer-stops" -> initializerTerminalEvent(true, false);
                case "initializer-revokes" -> initializerTerminalEvent(false, false);
                case "initializer-stops-off-thread" -> initializerTerminalEvent(true, true);
                case "initializer-revokes-off-thread" -> initializerTerminalEvent(false, true);
                case "queued-before-stop" -> queuedBeforeStop();
                case "revoked-cannot-reopen" -> revokedCannotReopen();
                case "startup-integrity" -> startupIntegrity();
                case "beta-context-pinned" -> betaContextPinned();
                case "beta-context-needs-memory" -> betaContextNeedsMemory();
                case "beta-context-too-late" -> betaContextTooLate();
                case "plus-reactivation-preserve" -> typedRejection(true,"reactivation_required");
                case "standard-reactivation-preserve" -> typedRejection(false,"reactivation_required");
                case "plus-concurrency-preserve" -> typedRejection(true,"concurrent_session_rejected");
                case "plus-token-revoked-preserve" -> typedRejection(true,"token_revoked");
                case "heartbeat-refresh" -> heartbeatRefresh();
                case "heartbeat-reject-401" -> heartbeatRejected(401);
                case "heartbeat-reject-403" -> heartbeatRejected(403);
                case "heartbeat-reject-409" -> heartbeatRejected(409);
                case "heartbeat-grace" -> heartbeatGrace();
                case "heartbeat-integrity" -> heartbeatIntegrity();
                case "heartbeat-security-rejection" -> heartbeatSecurityRejection();
                case "heartbeat-response-after-stop" -> heartbeatResponseAfterStop();
                case "heartbeat-scheduled" -> heartbeatScheduled();
                case "startup-needs-online-proof" -> startupNeedsOnlineProof();
                case "queued-session-expired" -> queuedSessionExpired();
                case "expiry-without-worker" -> expiryWithoutWorker();
                case "late-response-after-expiry" -> lateResponseAfterExpiry();
                default -> throw new IllegalArgumentException("Unknown scenario: " + args[0]);
            }
            System.out.println("PASS " + args[0]);
        } finally {
            worker().shutdownNow();
        }
    }

    private static void initialize() {
        require(CLIENT.isOnThread(), "Initializer must run on game thread");
        INITIALIZATIONS.incrementAndGet();
    }

    private static void cleanup() {
        require(CLIENT.isOnThread(), "Cleanup must run on game thread");
        CLEANUPS.incrementAndGet();
    }

    private static void startupDeferred() throws Exception {
        AuthBootstrap.install(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        require(ClientLifecycleEvents.CLIENT_STARTED.size() == 1, "One startup callback");
        require(ClientLifecycleEvents.CLIENT_STOPPING.size() == 1, "One stopping callback");
        workerBarrier();
        require(AuthServiceClient.authentications.get() == 0, "Normal install must defer authentication");
        require(INITIALIZATIONS.get() == 0 && !AuthBootstrap.authorized(), "No premature activation");
        ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);
        ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);
        completeAuthorization();
        ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);
        workerBarrier();
        require(AuthServiceClient.authentications.get() == 1 && INITIALIZATIONS.get() == 1, "Startup exactly once");
        require(AuthConfig.loads.get() == 1 && ArtifactIntegrity.calls.get() == 1, "Startup integrity gate invoked");
        require(HardwareFingerprint.collections.get() == 1 && CredentialStore.loads.get() == 1, "Stored identity verified");
    }

    private static void lateStartOnce() throws Exception {
        offThread(() -> AuthBootstrap.installAfterClientStarted(
            AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup));
        require(CLIENT.queuedCount() == 1, "Late entrypoint must schedule on game thread");
        require(AuthServiceClient.authentications.get() == 0, "No auth before scheduled late startup");
        require(ClientLifecycleEvents.CLIENT_STARTED.size() == 0, "Late startup must not await past event");
        require(ClientLifecycleEvents.CLIENT_STOPPING.size() == 1, "Late startup installs cleanup once");
        CLIENT.drain();
        invoke("start", new Class<?>[] {MinecraftClient.class}, CLIENT);
        ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);
        completeAuthorization();
        require(AuthServiceClient.authentications.get() == 1 && INITIALIZATIONS.get() == 1, "Late startup exactly once");
    }

    private static void duplicateOwner() throws Exception {
        AtomicInteger rejectedOwner = new AtomicInteger();
        AuthBootstrap.install(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        expect(IllegalStateException.class, () -> AuthBootstrap.installAfterClientStarted(
            rejectedOwner::incrementAndGet, rejectedOwner::incrementAndGet));
        require(ClientLifecycleEvents.CLIENT_STARTED.size() == 1 && ClientLifecycleEvents.CLIENT_STOPPING.size() == 1,
            "Rejected install must not add listeners");
        ClientLifecycleEvents.CLIENT_STARTED.fire(CLIENT);
        completeAuthorization();
        stop();
        require(INITIALIZATIONS.get() == 1 && CLEANUPS.get() == 1, "Original owner retained");
        require(rejectedOwner.get() == 0, "Rejected callbacks must never replace original owner");
    }

    private static void lateFailureObserver() throws Exception {
        AtomicReference<Throwable> observed = new AtomicReference<>();
        RuntimeException original = new IllegalStateException("fixture service failed");
        AuthBootstrap.installAfterClientStarted(() -> { initialize(); throw original; },
            AuthBootstrapLifecycleTest::cleanup, failure -> {
                require(CLIENT.isOnThread(), "Failure observer runs on the game thread");
                observed.set(failure);
            });
        await(() -> CLIENT.queuedCount() > 0, 2_000, "queued authorization");
        CLIENT.drain();
        require(observed.get() == original, "Late host receives the original startup failure");
        require(CLEANUPS.get() == 1 && !AuthBootstrap.authorized(), "Failure observation keeps cleanup and authorization gate");
        AuthBootstrap.stopAfterClientStarted().toCompletableFuture().get(2, TimeUnit.SECONDS);
        require(worker().isTerminated(), "Stop completion observes actual worker termination");
    }

    private static void lateScopedStop() throws Exception {
        var scope = dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.open();
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        completeAuthorization();
        require(AuthBootstrap.authorized(), "Scoped authentication still required and succeeds");
        AuthBootstrap.stopAfterClientStarted().toCompletableFuture().get(2, TimeUnit.SECONDS);
        scope.close();
        scope.detached().toCompletableFuture().get(2, TimeUnit.SECONDS);
        int cleaned = CLEANUPS.get();
        ClientLifecycleEvents.CLIENT_STOPPING.fire(CLIENT);
        require(CLEANUPS.get() == cleaned && cleaned == 1, "Detached stopping proxy does not call the retired runtime");
        require(ClientLifecycleEvents.CLIENT_STARTED.size() == 0 && worker().isTerminated(), "Late scope leaves no startup callback or worker");
    }

    private static void initializerFailure(boolean linkage) throws Exception {
        AuthBootstrap.installAfterClientStarted(() -> {
            initialize();
            if (linkage) throw new LinkageError("fixture linkage failure");
            throw new IllegalStateException("fixture initialization failure");
        }, AuthBootstrapLifecycleTest::cleanup);
        await(() -> CLIENT.queuedCount() > 0, 2_000, "queued authorization");
        CLIENT.drain();
        workerBarrier();
        require(INITIALIZATIONS.get() == 1 && CLEANUPS.get() == 1, "Failed initializer cleaned exactly once");
        assertClosedSession();
        require(AuthBootstrap.status().contains("initialization failed"), "Initialization failure status exposed");
        assertCannotReopen();
    }

    private static void queuedBeforeStop() throws Exception {
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        await(() -> CLIENT.queuedCount() > 0, 2_000, "worker auth response queued");
        require(!AuthBootstrap.authorized(), "Worker must not publish authorization before game task");
        stop();
        CLIENT.drain();
        require(INITIALIZATIONS.get() == 0 && CLEANUPS.get() == 1, "Queued response cannot activate after stop");
        require(worker().isShutdown(), "Stop must shut heartbeat worker down");
        assertClosedSession();
        assertCannotReopen();
        stop();
        require(CLEANUPS.get() == 1, "Repeated stopping event is idempotent");
    }

    private static void initializerTerminalEvent(boolean stopping, boolean externalThread) throws Exception {
        AuthBootstrap.installAfterClientStarted(() -> {
            initialize();
            require(LicenseIdentity.uid() == 42L, "Verified identity must precede initializer");
            CheckedAction terminalEvent = stopping ? AuthBootstrapLifecycleTest::stop
                : () -> invoke("lockSession", new Class<?>[] {String.class}, "fixture revoked during initialization");
            try {
                if (externalThread) offThread(terminalEvent);
                else terminalEvent.run();
            } catch (Exception exception) {
                throw new IllegalStateException("Fixture terminal event failed", exception);
            }
            require(!AuthBootstrap.authorized() && AuthBootstrap.locked(), "Terminal event locks before initializer returns");
            if (!externalThread) {
                require(LicenseIdentity.uid() == 0L, "Direct terminal event clears identity during initializer");
            } else {
                require(CLIENT.queuedCount() > 0, "Off-thread terminal event schedules game-thread cleanup");
            }
            // Return normally: the production bootstrap must not publish success after this point.
        }, AuthBootstrapLifecycleTest::cleanup);
        await(() -> CLIENT.queuedCount() > 0, 2_000, "queued authorization");
        CLIENT.drain();
        if (!stopping) workerBarrier();
        require(INITIALIZATIONS.get() == 1 && CLEANUPS.get() == 1, "Mid-initializer terminal event cleans once");
        assertClosedSession();
        require(!"Authorized".equals(AuthBootstrap.status()), "Initializer return must not overwrite terminal status with Authorized");
        if (!stopping) {
            require("fixture revoked during initialization".equals(AuthBootstrap.status()), "Revocation status survives initializer return");
        }
        assertCannotReopen();
        require(LicenseIdentity.uid() == 0L, "Initializer completion and stale responses must not repopulate identity");
    }

    private static void revokedCannotReopen() throws Exception {
        activate();
        invoke("lockSession", new Class<?>[] {String.class}, "fixture revoked");
        require(CLEANUPS.get() == 1, "Revocation invokes cleanup");
        assertClosedSession();
        assertCannotReopen();
    }

    private static void startupIntegrity() throws Exception {
        ArtifactIntegrity.observed = "fixture-tampered";
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        workerBarrier();
        CLIENT.drain();
        require(!AuthBootstrap.authorized() && INITIALIZATIONS.get() == 0, "Tampered startup never initializes");
        require(AuthServiceClient.authentications.get() == 0, "Integrity mismatch must stop session authentication");
        require(AuthServiceClient.tamperReports.get() == 1, "Existing binding receives tamper report");
        require(HardwareFingerprint.collections.get() == 0, "Failed integrity gate stops later initialization");
        require(AuthBootstrap.status().contains("integrity verification failed"), "Integrity failure retained");
    }

    private static void typedRejection(boolean plus,String code)throws Exception {
        if(plus){AuthBootstrap.configureBetaRuntime("a".repeat(64));ArtifactIntegrity.retainedMemory=true;}
        activate();
        var rejected=new AuthServiceClient.AuthRejectedException(401,code);
        AuthServiceClient.checkFailure=rejected;heartbeat();assertClosedSession();
        require(CLEANUPS.get()==1,"typed failure always retires active gameplay exactly once");
        require(CredentialStore.clears.get()==0&&CredentialStore.present,"remote token/protocol errors preserve durable keys for fresh dual auth");
    }

    private static void heartbeatRefresh() throws Exception {
        activate();
        heartbeat();
        require(AuthServiceClient.checks.get() == 2 && ArtifactIntegrity.calls.get() == 2, "Startup and heartbeat each verify a fresh server session");
        require("refreshed-grant".equals(field("sessionGrant")), "Heartbeat refreshes grant");
        require(AuthBootstrap.authorized() && CLEANUPS.get() == 0, "Successful heartbeat preserves active client");
        String grant = new String(AuthBootstrap.cloudRequest("GET", "/fixture", null, null).body(),
            java.nio.charset.StandardCharsets.UTF_8);
        require(grant.equals("refreshed-grant"), "Cloud requests use refreshed session grant");
    }

    private static void betaContextPinned() throws Exception {
        String verifiedCatalog = "a".repeat(64);
        expect(IllegalArgumentException.class, () -> AuthBootstrap.configureBetaRuntime(null));
        expect(IllegalArgumentException.class, () -> AuthBootstrap.configureBetaRuntime("invalid"));
        AuthBootstrap.configureBetaRuntime(verifiedCatalog);
        expect(IllegalStateException.class, () -> AuthBootstrap.configureBetaRuntime("b".repeat(64)));
        ArtifactIntegrity.retainedMemory = true;
        activate();
        require(verifiedCatalog.equals(AuthServiceClient.receivedBetaCatalog), "Verified catalog reaches runtime auth unchanged");
        require(ArtifactIntegrity.calls.get() == 1, "Memory beta still computes integrity before auth");
        expect(IllegalStateException.class, () -> AuthBootstrap.configureBetaRuntime(verifiedCatalog));
    }

    private static void betaContextNeedsMemory() throws Exception {
        AuthBootstrap.configureBetaRuntime("a".repeat(64));
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        workerBarrier();
        CLIENT.drain();
        require(!AuthBootstrap.authorized() && INITIALIZATIONS.get() == 0, "Beta cannot start from a missing retained source");
        require(ArtifactIntegrity.calls.get() == 0, "Missing beta source must not pin disk integrity");
        require(AuthServiceClient.authentications.get() == 0 && AuthServiceClient.tamperReports.get() == 0,
            "Missing integration context must not authenticate or report a license for tampering");
        require(HardwareFingerprint.collections.get() == 0, "Reject incomplete beta host before hardware collection");
    }

    private static void betaContextTooLate() throws Exception {
        activate();
        expect(IllegalStateException.class, () -> AuthBootstrap.configureBetaRuntime("a".repeat(64)));
        require(AuthServiceClient.receivedBetaCatalog == null, "Production context remains production");
        require(AuthBootstrap.authorized(), "Rejected context change does not disrupt the existing session");
    }

    private static void heartbeatSecurityRejection() throws Exception {
        activate();
        AuthServiceClient.checkFailure = new java.security.GeneralSecurityException("Malformed beta session context");
        heartbeat();
        assertClosedSession();
        require(CLEANUPS.get() == 1, "Invalid signed context has no network outage grace");
    }

    private static void heartbeatRejected(int code) throws Exception {
        activate();
        AuthServiceClient.checkFailure = new AuthServiceClient.AuthRejectedException(code, "fixture rejected");
        heartbeat();
        assertClosedSession();
        require(CLEANUPS.get() == 1, "Rejected heartbeat cleans active client");
    }

    private static void heartbeatGrace() throws Exception {
        activate();
        AuthServiceClient.checkFailure = new IOException("fixture offline");
        heartbeat();
        require(AuthBootstrap.authorized() && CLEANUPS.get() == 0, "Transient outage retains grace period");
        setField("sessionDeadlineNanos", System.nanoTime() - 1);
        heartbeat();
        assertClosedSession();
        require(CLEANUPS.get() == 1, "Expired heartbeat grace cleans active client");
    }

    private static void heartbeatIntegrity() throws Exception {
        activate();
        ArtifactIntegrity.observed = "fixture-tampered-after-start";
        heartbeat();
        assertClosedSession();
        require(AuthServiceClient.tamperReports.get() == 1 && CLEANUPS.get() == 1, "Heartbeat tamper reports and locks");
    }

    private static void heartbeatResponseAfterStop() throws Exception {
        activate();
        AuthServiceClient.checkEntered = new CountDownLatch(1);
        AuthServiceClient.checkRelease = new CountDownLatch(1);
        AuthServiceClient.ignoreCheckInterrupts = true;
        Future<?> inFlight = worker().submit(() -> {
            try { heartbeat(); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        require(AuthServiceClient.checkEntered.await(2, TimeUnit.SECONDS), "Heartbeat request must enter fixture");
        stop();
        AuthServiceClient.checkRelease.countDown();
        inFlight.get(2, TimeUnit.SECONDS);
        CLIENT.drain();
        assertClosedSession();
        require(INITIALIZATIONS.get() == 1 && CLEANUPS.get() == 1, "In-flight refresh cannot reopen stopped session");
    }

    private static void heartbeatScheduled() throws Exception {
        activate();
        System.out.println("Waiting for production 30-second heartbeat schedule...");
        await(() -> AuthServiceClient.checks.get() > 1, 35_000, "scheduled heartbeat");
        await(() -> "refreshed-grant".equals(uncheckedField("sessionGrant")), 2_000, "scheduled grant refresh");
        require(AuthBootstrap.authorized() && ArtifactIntegrity.calls.get() >= 2, "Scheduled heartbeat keeps verification active");
        stop();
        require(worker().isShutdown(), "Scheduled worker stops with client");
    }

    private static void activate() throws Exception {
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        completeAuthorization();
    }

    private static void startupNeedsOnlineProof() throws Exception {
        AuthServiceClient.checkFailure = new IOException("offline response replay fixture");
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        workerBarrier(); CLIENT.drain();
        require(AuthServiceClient.authentications.get() == 1 && AuthServiceClient.checks.get() == 1,
            "A captured startup grant must still perform the nonce-bound online check");
        require(!AuthBootstrap.authorized() && INITIALIZATIONS.get() == 0,
            "Offline startup cannot initialize from a saved credential or grant");
    }

    private static void queuedSessionExpired() throws Exception {
        AuthServiceClient.validityNanos = 1;
        AuthBootstrap.installAfterClientStarted(AuthBootstrapLifecycleTest::initialize, AuthBootstrapLifecycleTest::cleanup);
        workerBarrier(); CLIENT.drain();
        require(!AuthBootstrap.authorized() && INITIALIZATIONS.get() == 0,
            "Online response expiring in the game queue cannot initialize later");
    }

    private static void expiryWithoutWorker() throws Exception {
        activate();
        worker().shutdownNow();
        setField("sessionDeadlineNanos", System.nanoTime() - 1);
        require(!AuthBootstrap.authorized(), "Gameplay expires the server lease without the heartbeat worker");
        CLIENT.drain(); assertClosedSession();
        require(CLEANUPS.get() == 1, "Worker failure still cleans exactly once");
    }

    private static void lateResponseAfterExpiry() throws Exception {
        activate();
        AuthServiceClient.checkEntered = new CountDownLatch(1);
        AuthServiceClient.checkRelease = new CountDownLatch(1);
        Future<?> request = worker().submit(() -> {
            try { heartbeat(); } catch (Exception error) { throw new IllegalStateException(error); }
        });
        require(AuthServiceClient.checkEntered.await(2, TimeUnit.SECONDS), "Refresh is in flight");
        setField("sessionDeadlineNanos", System.nanoTime() - 1);
        require(!AuthBootstrap.authorized(), "Expiry closes while refresh waits");
        AuthServiceClient.checkRelease.countDown(); request.get(2, TimeUnit.SECONDS);
        CLIENT.drain(); assertClosedSession();
        require(INITIALIZATIONS.get() == 1 && CLEANUPS.get() == 1, "Late response cannot reopen expired owner");
    }

    private static void completeAuthorization() throws Exception {
        await(() -> CLIENT.queuedCount() > 0, 2_000, "queued authorization");
        require(!AuthBootstrap.authorized(), "Authorization must publish on game thread only");
        CLIENT.drain();
        workerBarrier();
        require(AuthBootstrap.authorized() && INITIALIZATIONS.get() == 1 && LicenseIdentity.uid() == 42L,
            "Successful fixture authentication activates owner");
    }

    private static void assertCannotReopen() throws Exception {
        int initializedBefore = INITIALIZATIONS.get();
        int authBefore = AuthServiceClient.authentications.get();
        int clearBefore = CredentialStore.clears.get();
        AuthBootstrap.retry();
        AuthBootstrap.submitLicense("fixture-license-not-a-real-license");
        AuthBootstrap.clearLocalBinding();
        invoke("authorize", new Class<?>[] {MinecraftClient.class, AuthServiceClient.Authenticated.class, InstallCredential.class},
            CLIENT, new AuthServiceClient.Authenticated(43L, "stale-grant"), new InstallCredential());
        invoke("start", new Class<?>[] {MinecraftClient.class}, CLIENT);
        CLIENT.drain();
        require(INITIALIZATIONS.get() == initializedBefore && AuthServiceClient.authentications.get() == authBefore,
            "Terminal state refuses fresh and stale authentication");
        require(CredentialStore.clears.get() == clearBefore && AuthServiceClient.enrollments.get() == 0,
            "Terminal state cannot mutate binding or enroll");
        assertClosedSession();
    }

    private static void assertClosedSession() throws Exception {
        require(!AuthBootstrap.authorized() && AuthBootstrap.locked(), "Session must fail closed");
        require(field("sessionCredential") == null && field("sessionGrant") == null, "Session secrets cleared");
        require(LicenseIdentity.uid() == 0L, "Displayed identity cleared");
        expect(IOException.class, () -> AuthBootstrap.cloudRequest("GET", "/fixture", null, null));
    }

    private static void stop() { ClientLifecycleEvents.CLIENT_STOPPING.fire(CLIENT); }
    private static void heartbeat() throws Exception { invoke("heartbeat", new Class<?>[0]); }
    private static ScheduledExecutorService worker() throws Exception { return (ScheduledExecutorService) field("WORKER"); }
    private static void workerBarrier() throws Exception { worker().submit(() -> { }).get(2, TimeUnit.SECONDS); }

    private static Object field(String name) throws Exception {
        Field field = AuthBootstrap.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static Object uncheckedField(String name) {
        try { return field(name); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void setField(String name, Object value) throws Exception {
        Field field = AuthBootstrap.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void invoke(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = AuthBootstrap.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try { method.invoke(null, args); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }

    private static void offThread(CheckedAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { action.run(); }
            catch (Throwable throwable) { failure.set(throwable); }
        }, "fixture-external-caller");
        caller.start();
        caller.join(2_000);
        require(!caller.isAlive(), "External caller completed");
        if (failure.get() != null) throw new AssertionError("External call failed", failure.get());
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5L);
        require(condition.getAsBoolean(), "Timed out waiting for " + label);
    }

    private static void expect(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        try { action.run(); }
        catch (Throwable throwable) {
            if (type.isInstance(throwable)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + ", got " + throwable, throwable);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
