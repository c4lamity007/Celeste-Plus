package dev.c4lam.celeste.auth;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.MinecraftClient;

final class AuthServiceClient implements AutoCloseable {
    static final AtomicInteger closed = new AtomicInteger();
    void releaseCredential(InstallCredential credential) { }
    @Override public void close() { closed.incrementAndGet(); }
    static final AtomicInteger authentications = new AtomicInteger();
    static final AtomicInteger enrollments = new AtomicInteger();
    static final AtomicInteger checks = new AtomicInteger();
    static final AtomicInteger tamperReports = new AtomicInteger();
    static final AtomicInteger fatalChecks = new AtomicInteger();
    static volatile Exception checkFailure;
    static volatile String nextGrant = "refreshed-grant";
    static volatile CountDownLatch checkEntered;
    static volatile CountDownLatch checkRelease;
    static volatile boolean ignoreCheckInterrupts;
    static volatile String receivedBetaCatalog;
    static volatile long validityNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(90);

    long sessionValidityNanos(String sessionToken) { return validityNanos; }

    record Authenticated(long uid, String grant) { }
    record Enrollment(InstallCredential credential) { }
    record CloudResponse(String contentType, byte[] body) { }

    AuthServiceClient(AuthConfig config, GameIdentity identity) { }
    AuthServiceClient(AuthConfig config, GameIdentity identity, String expectedBetaCatalog) {
        receivedBetaCatalog = expectedBetaCatalog;
    }

    Enrollment enroll(String license, HardwareFingerprint hardware) {
        enrollments.incrementAndGet();
        return new Enrollment(new InstallCredential());
    }

    Authenticated authenticate(InstallCredential credential, HardwareFingerprint hardware) {
        if (MinecraftClient.getInstance().isOnThread()) throw new AssertionError("Network auth ran on game thread");
        authentications.incrementAndGet();
        return new Authenticated(42L, "initial-grant");
    }

    String checkSession(InstallCredential credential, String digest, boolean tampered) throws Exception {
        checks.incrementAndGet();
        if (tampered) tamperReports.incrementAndGet();
        CountDownLatch entered = checkEntered;
        CountDownLatch release = checkRelease;
        if (entered != null) entered.countDown();
        if (release != null) {
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; }
                catch (InterruptedException exception) {
                    if (!ignoreCheckInterrupts) throw exception;
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (fatalChecks.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            throw new LinkageError("fixture heartbeat task failure");
        }
        if (checkFailure != null) throw checkFailure;
        return nextGrant;
    }

    CloudResponse cloudRequest(String method, String path, String contentType, byte[] body, String grant) {
        return new CloudResponse("text/plain", grant.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static final class AuthRejectedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;
        AuthRejectedException(int code, String message) { super(message); this.code=code; }
        int statusCode() { return code; }
    }
}
