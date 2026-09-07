package fixture;

import dev.c4lam.celeste.auth.ArtifactIntegrity;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.compat.BetaRenderPipelines;
import dev.c4lam.celeste.compat.BetaTextureLease;
import dev.c4lam.celeste.beta.lifecycle.BetaPayloadSession;
import dev.c4lam.celeste.beta.lifecycle.BetaRuntime;
import dev.c4lam.celeste.beta.resources.InternalMemoryResources;
import dev.c4lam.celeste.beta.resources.MinecraftInternalResourcesHost;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.MinecraftClient;

public final class SessionCheck {
    public static final String DIGEST = "a".repeat(64);
    private static int checks;
    private SessionCheck() { }

    public static void main(String[] args) {
        normalStop();
        stopDuringActivation();
        badDigest();
        authFailure();
        removalFailure();
        pipelineCleanupFailure();
        activationFailure();
        failedScheduling();
        System.out.println("Payload session: " + checks + " checks passed; production orchestration with controlled game/auth boundaries");
    }

    private static BetaPayloadSession begin() {
        reset();
        return BetaPayloadSession.start(visitor -> {
            visitor.accept(Map.of("assets/celeste/test.png", new byte[] { 7 }));
            visitor.accept(Map.of("dev/c4lam/celeste/Example.class", new byte[] { 1 }));
        }, "b".repeat(64), DIGEST);
    }
    private static void normalStop() {
        BetaPayloadSession session = begin();
        pump();
        check(BetaRenderPipelines.active && BetaRenderPipelines.opens == 1, "pipeline lease acquired before static GUI registration");
        check(AuthBootstrap.configured && ArtifactIntegrity.installed, "retained auth context configured");
        check(BetaRuntime.starts == 0 && !done(session.ready()), "auth waits for observed assets reload");
        check(InternalMemoryResources.lastByte == 7, "assets derived from retained maps");
        MinecraftInternalResourcesHost.activation.complete(null); pump();
        check(BetaRuntime.starts == 1 && !done(session.ready()), "not ready just because auth started");
        BetaRuntime.current.ready.complete(null); pump();
        check(done(session.ready()) && !done(session.stopped()), "ready after authorized initialization");
        session.stop(); pump();
        check(BetaRenderPipelines.active && BetaRenderPipelines.closes == 0, "pipelines retained until service callbacks are quiescent");
        check(MinecraftInternalResourcesHost.removals == 0, "assets retained while service stop is pending");
        BetaRuntime.current.stopped.complete(null); pump();
        check(!BetaRenderPipelines.active && BetaRenderPipelines.closes == 1, "pipeline requirements retired before replacement reload");
        check(MinecraftInternalResourcesHost.removals == 1 && !InternalMemoryResources.closed, "replacement reload precedes resource release");
        MinecraftInternalResourcesHost.removal.complete(null); pump();
        check(done(session.stopped()) && !session.stopped().toCompletableFuture().isCompletedExceptionally(), "normal stop settles");
        check(InternalMemoryResources.closed && MinecraftInternalResourcesHost.closes == 1, "host and owned assets released");
        session.stop(); pump();
        check(BetaTextureLease.closes == 1, "retired texture cache views are released once");
        check(BetaRenderPipelines.closes == 1, "repeated Stop retires registrations once");
        check(MinecraftInternalResourcesHost.removals == 1 && MinecraftInternalResourcesHost.closes == 1, "repeated stop does not repeat cleanup");
    }
    private static void stopDuringActivation() {
        BetaPayloadSession session = begin(); pump();
        session.stop(); pump();
        check(!done(session.stopped()) && !InternalMemoryResources.closed, "stop waits for in-flight activation");
        MinecraftInternalResourcesHost.activation.complete(null); pump();
        check(BetaRuntime.starts == 0 && MinecraftInternalResourcesHost.removals == 1, "cancel before auth removes assets without starting runtime");
        MinecraftInternalResourcesHost.removal.complete(null); pump();
        check(done(session.stopped()) && !session.stopped().toCompletableFuture().isCompletedExceptionally(), "cancelled startup retires normally");
        check(session.ready().toCompletableFuture().isCompletedExceptionally(), "cancelled startup never reports ready");
    }
    private static void badDigest() {
        reset();
        BetaPayloadSession session = BetaPayloadSession.start(visitor -> { throw new AssertionError("assets must not be visited"); }, "b".repeat(64), "c".repeat(64));
        pump();
        check(session.ready().toCompletableFuture().isCompletedExceptionally(), "digest mismatch denies startup");
        check(!AuthBootstrap.configured && !ArtifactIntegrity.installed && BetaRuntime.starts == 0, "mismatch occurs before auth/context installation");
        check(done(session.stopped()), "preparation failure settles retirement");
    }
    private static void authFailure() {
        BetaPayloadSession session = begin(); pump();
        MinecraftInternalResourcesHost.activation.complete(null); pump();
        BetaRuntime.current.ready.complete(null); pump();
        IOException revoked = new IOException("license revoked");
        BetaRuntime.current.stopped.completeExceptionally(revoked); pump();
        check(MinecraftInternalResourcesHost.removals == 1, "revocation after ready triggers asset removal");
        MinecraftInternalResourcesHost.removal.complete(null); pump();
        check(InternalMemoryResources.closed && done(session.stopped()), "revocation still releases resources");
        check(cause(session.stopped()) == revoked, "original auth failure remains observable");
    }
    private static void removalFailure() {
        BetaPayloadSession session = begin(); pump();
        MinecraftInternalResourcesHost.activation.complete(null); pump();
        BetaRuntime.current.ready.complete(null); pump();
        session.stop(); pump(); BetaRuntime.current.stopped.complete(null); pump();
        IOException broken = new IOException("replacement reload failed");
        MinecraftInternalResourcesHost.removal.completeExceptionally(broken); pump();
        check(!InternalMemoryResources.closed && MinecraftInternalResourcesHost.closes == 0, "failed replacement retains manager-owned bytes");
        check(cause(session.stopped()) == broken, "failed replacement is not a successful stop");
    }
    private static void pipelineCleanupFailure() {
        BetaPayloadSession session = begin(); pump();
        MinecraftInternalResourcesHost.activation.complete(null); pump();
        BetaRuntime.current.ready.complete(null); pump();
        BetaRenderPipelines.failClose = true;
        session.stop(); pump(); BetaRuntime.current.stopped.complete(null); pump();
        check(MinecraftInternalResourcesHost.removals == 0 && !InternalMemoryResources.closed,
            "failed pipeline cleanup retains shader assets and prevents a broken resource reload");
        check(cause(session.stopped()) instanceof IllegalStateException, "failed pipeline cleanup remains observable");
    }
    private static void activationFailure() {
        BetaPayloadSession session = begin(); pump();
        IOException failure = new IOException("shader compile failed");
        MinecraftInternalResourcesHost.activation.completeExceptionally(failure); pump();
        check(BetaRuntime.starts == 0 && MinecraftInternalResourcesHost.removals == 1, "failed shader load cannot start auth/services");
        MinecraftInternalResourcesHost.removal.complete(null); pump();
        check(InternalMemoryResources.closed && cause(session.ready()) == failure, "activation failure cleans up and retains cause");
    }
    private static void failedScheduling() {
        reset(); MinecraftClient.reject = true;
        BetaPayloadSession session = BetaPayloadSession.start(visitor -> { }, "b".repeat(64), DIGEST);
        check(done(session.ready()) && done(session.stopped()), "failed client scheduling completes observers");
        check(session.stopped().toCompletableFuture().isCompletedExceptionally(), "scheduling failure is not successful retirement");
        MinecraftClient.reject = false;
    }
    private static void reset() {
        MinecraftClient.queue.clear(); MinecraftClient.reject = false;
        BetaRenderPipelines.active = BetaRenderPipelines.failClose = false;
        BetaRenderPipelines.opens = BetaRenderPipelines.closes = 0;
        BetaTextureLease.opens = BetaTextureLease.closes = 0;
        AuthBootstrap.configured = false; ArtifactIntegrity.installed = false;
        InternalMemoryResources.closed = false; InternalMemoryResources.lastByte = -1;
        MinecraftInternalResourcesHost.activation = new CompletableFuture<>();
        MinecraftInternalResourcesHost.removal = new CompletableFuture<>();
        MinecraftInternalResourcesHost.removals = MinecraftInternalResourcesHost.closes = 0;
        BetaRuntime.starts = 0; BetaRuntime.current = null;
    }
    private static void pump() {
        int budget = 1000;
        while (!MinecraftClient.queue.isEmpty()) {
            if (--budget == 0) throw new AssertionError("runaway client scheduling");
            MinecraftClient.queue.removeFirst().run();
        }
    }
    private static boolean done(java.util.concurrent.CompletionStage<Void> stage) { return stage.toCompletableFuture().isDone(); }
    private static Throwable cause(java.util.concurrent.CompletionStage<Void> stage) {
        try { stage.toCompletableFuture().join(); }
        catch (CompletionException error) { return error.getCause(); }
        throw new AssertionError("expected failure");
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
