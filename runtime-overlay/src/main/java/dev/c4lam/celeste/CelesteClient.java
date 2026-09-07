package dev.c4lam.celeste;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaCoreCallbacks;
import dev.c4lam.celeste.beta.lifecycle.BetaInteractionHooks;
import dev.c4lam.celeste.beta.lifecycle.BetaRotationHooks;
import dev.c4lam.celeste.beta.lifecycle.BetaAtmosphereHooks;
import dev.c4lam.celeste.beta.lifecycle.BetaGameplayCallbacks;
import dev.c4lam.celeste.beta.lifecycle.BetaFakeGambleHooks;
import dev.c4lam.celeste.beta.lifecycle.BetaRuntimeLifecycle;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.beta.visual.BetaVisualHooks;
import dev.c4lam.celeste.config.ConfigLibrary;
import dev.c4lam.celeste.config.ConfigProfileManager;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.module.BuiltInFeatures;
import dev.c4lam.celeste.module.ModuleManager;
import dev.c4lam.celeste.module.impl.visual.expansion.UnifiedChamsRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.UnifiedVolumeRenderer;
import dev.c4lam.celeste.render.GuiPipelines;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CelesteClient implements ClientModInitializer {
    public static final String MOD_ID = "celeste";
    public static final String NAME = "Celeste Client";
    public static final String VERSION = "1.0";

    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/Lifecycle");
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static volatile boolean active;

    private boolean initialized;
    private boolean initializationAttempted;
    private boolean closed;
    private boolean menuKeyDown;
    private volatile boolean lateMode;
    private volatile boolean ownsInstallation;
    private final BetaRuntimeLifecycle lateLifecycle = new BetaRuntimeLifecycle();
    private BetaCoreCallbacks betaCoreCallbacks;
    private BetaSubscriptions betaSubscriptions;
    private ConfigLibrary configLibrary;
    private SystemMediaService mediaService;
    private final Deque<Runnable> cleanup = new ArrayDeque<>();

    @Override
    public void onInitializeClient() {
        install(false);
    }

    /** Called only after a host has supplied compatible game hooks and resources. */
    public void initializeAfterGameStarted() {
        startAfterGameStarted();
    }

    /** Ready only after successful authorization and complete client-service initialization. */
    public CompletionStage<Void> startAfterGameStarted() {
        if (ownsInstallation && !lateMode) return CompletableFuture.failedFuture(
            new IllegalStateException("This client was installed through normal startup"));
        lateMode = true;
        if (lateLifecycle.beginStart()) {
            try {
                net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                    if (lateLifecycle.stopping()) {
                        shutdownAuthorizedClient();
                        return;
                    }
                    try { install(true); }
                    catch (RuntimeException | LinkageError failure) { failLateRuntime(failure); }
                });
            } catch (RuntimeException | LinkageError failure) {
                lateLifecycle.stopSchedulingFailed(failure);
            }
        }
        return lateLifecycle.ready();
    }

    public CompletionStage<Void> lateReady() { return lateLifecycle.ready(); }
    public CompletionStage<Void> lateStopped() { return lateLifecycle.stopped(); }

    public Map<String, String> lateStatus() {
        Map<String, String> snapshot = new HashMap<>(lateLifecycle.snapshot());
        snapshot.put("authentication", lateMode && ownsInstallation ? AuthBootstrap.status() : "Not installed");
        return Map.copyOf(snapshot);
    }

    /** Stops this late runtime only; never closes Minecraft or changes another installed client. */
    public CompletionStage<Void> stopAfterGameStarted() {
        if (!lateMode) return CompletableFuture.failedFuture(new IllegalStateException("This is not a late runtime"));
        lateLifecycle.requestStop();
        try {
            net.minecraft.client.MinecraftClient.getInstance().execute(this::shutdownAuthorizedClient);
        } catch (RuntimeException | LinkageError failure) {
            lateLifecycle.stopSchedulingFailed(failure);
        }
        return lateLifecycle.stopped();
    }

    private void failLateRuntime(Throwable failure) {
        lateLifecycle.fail(failure);
        shutdownAuthorizedClient();
    }

    public static boolean active() {
        return active && AuthBootstrap.authorized();
    }

    /** Core callback retirement only; the late host must also retire all other runtime services. */
    public java.util.concurrent.CompletionStage<Void> coreCallbacksDetached() {
        return betaCoreCallbacks == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
            .minimalCompletionStage() : betaCoreCallbacks.detached();
    }

    /** Retirement of scoped module/HUD/world callbacks, separate from core and authorization services. */
    public java.util.concurrent.CompletionStage<Void> subscriptionsDetached() {
        return betaSubscriptions == null ? java.util.concurrent.CompletableFuture.<Void>completedFuture(null)
            .minimalCompletionStage() : betaSubscriptions.detached();
    }

    private void install(boolean alreadyStarted) {
        if (!INSTALLED.compareAndSet(false, true)) {
            if (alreadyStarted) throw new IllegalStateException("A Celeste runtime is already installed in this payload");
            return;
        }
        ownsInstallation = true;
        cleanup.push(RenderUtil::cleanup);
        try {
            if (alreadyStarted) {
                betaSubscriptions = BetaSubscriptions.open();
                cleanup.push(betaSubscriptions::close);
            }
            GuiPipelines.register();
            SynchronousResourceReloader fontListener = manager -> {
                if (!closed) {
                    Fonts.reload();
                }
            };
            ClientTickEvents.EndTick tickListener = client -> {
                if (!initialized || !active()) {
                    menuKeyDown = false;
                    return;
                }
                boolean pressed = InputUtil.isKeyPressed(client.getWindow(), InterfaceConfig.menuKey());
                if (pressed && !menuKeyDown && client.currentScreen == null) {
                    client.setScreen(new CelesteScreen());
                }
                menuKeyDown = pressed;
            };
            if (alreadyStarted) {
                betaCoreCallbacks = BetaCoreCallbacks.connect(fontListener, tickListener);
                cleanup.push(betaCoreCallbacks::close);
            } else {
                ResourceLoader.get(ResourceType.CLIENT_RESOURCES).registerReloader(Identifier.of(MOD_ID, "fonts"), fontListener);
                ClientTickEvents.END_CLIENT_TICK.register(tickListener);
            }
            if (alreadyStarted) {
                cleanup.push(BetaVisualHooks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                cleanup.push(BetaInteractionHooks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                cleanup.push(BetaRotationHooks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                cleanup.push(BetaAtmosphereHooks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                cleanup.push(BetaGameplayCallbacks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                cleanup.push(BetaFakeGambleHooks.connect(net.minecraft.client.MinecraftClient.getInstance())::close);
                AuthBootstrap.installAfterClientStarted(this::initializeAuthorizedClient, this::shutdownAuthorizedClient,
                    this::failLateRuntime);
            } else {
                AuthBootstrap.install(this::initializeAuthorizedClient, this::shutdownAuthorizedClient);
            }
        } catch (RuntimeException | LinkageError exception) {
            if (lateMode) lateLifecycle.fail(exception);
            shutdownAuthorizedClient();
            throw exception;
        }
    }

    private void initializeAuthorizedClient() {
        if (initializationAttempted || closed || lateMode && lateLifecycle.stopping()) {
            throw new IllegalStateException("Celeste initialization requires a fresh game session");
        }
        initializationAttempted = true;
        // Register each cleanup before the corresponding step, including partial failures.
        cleanup.push(InterfaceConfig::close);
        InterfaceConfig.initialize();
        cleanup.push(() -> FriendManager.getInstance().close());
        FriendManager.getInstance().initialize();
        cleanup.push(() -> HudManager.getInstance().close());
        HudManager.getInstance().initialize();
        mediaService = SystemMediaService.getInstance();
        cleanup.push(() -> SystemMediaService.getInstance().close());
        SystemMediaService.getInstance().initialize();
        cleanup.push(() -> GameplayFoundation.close(net.minecraft.client.MinecraftClient.getInstance()));
        GameplayFoundation.initialize();
        ModuleManager modules = ModuleManager.getInstance();
        // LIFO: stop features, then retire their GPU storage before detaching lifecycle callbacks.
        cleanup.push(UnifiedChamsRenderer::releaseResources);
        cleanup.push(UnifiedVolumeRenderer::releaseResources);
        cleanup.push(dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature::shutdown);
        cleanup.push(modules::close);
        BuiltInFeatures.register(modules);
        modules.initialize();
        configLibrary = ConfigLibrary.getInstance();
        cleanup.push(configLibrary::close);
        ConfigProfileManager.getInstance().initialize();
        HudManager.getInstance().synchronizeModuleBackedElements();
        if (closed || !AuthBootstrap.authorized()) {
            shutdownAuthorizedClient();
            throw new IllegalStateException("Authorization ended during client initialization");
        }
        initialized = true;
        active = true;
        if (lateMode && !lateLifecycle.activate()) {
            shutdownAuthorizedClient();
            throw new IllegalStateException("The beta runtime stopped during initialization");
        }
    }

    private void shutdownAuthorizedClient() {
        if (closed) {
            return;
        }
        closed = true;
        if (lateMode) lateLifecycle.requestStop();
        if (!ownsInstallation) {
            if (lateMode) lateLifecycle.finish();
            return;
        }
        active = false;
        initialized = false;
        menuKeyDown = false;
        CompletionStage<Void> authorizationStopped = CompletableFuture.completedFuture(null);
        if (lateMode) {
            try { authorizationStopped = AuthBootstrap.stopAfterClientStarted(); }
            catch (RuntimeException | LinkageError failure) { lateLifecycle.fail(failure); }
        }
        while (!cleanup.isEmpty()) {
            try {
                cleanup.pop().run();
            } catch (RuntimeException | LinkageError exception) {
                LOGGER.error("Client cleanup step failed", exception);
                if (lateMode) lateLifecycle.fail(exception);
            }
        }
        try {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.currentScreen instanceof CelesteScreen || client.currentScreen instanceof HudEditorScreen) client.setScreen(null);
        } catch (RuntimeException | LinkageError failure) {
            if (!lateMode) throw failure;
            lateLifecycle.fail(failure);
        }
        if (lateMode) {
            CompletableFuture.allOf(authorizationStopped.toCompletableFuture(), coreCallbacksDetached().toCompletableFuture(),
                subscriptionsDetached().toCompletableFuture(),
                configLibrary == null ? CompletableFuture.completedFuture(null) : configLibrary.stopped().toCompletableFuture(),
                mediaService == null ? CompletableFuture.completedFuture(null) : mediaService.stopped().toCompletableFuture(),
                dev.c4lam.celeste.beta.lifecycle.BetaNetworkRetirement.drained().toCompletableFuture(),
                dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature.importsStopped().toCompletableFuture())
                .whenComplete(this::finishLateShutdown);
        }
    }

    private void finishLateShutdown(Void ignored, Throwable failure) {
        if (failure != null) lateLifecycle.fail(failure instanceof CompletionException && failure.getCause() != null
            ? failure.getCause() : failure);
        try {
            // Worker completions may already have queued UI callbacks. Drain those
            // before the late host is allowed to retire the payload's class resources.
            net.minecraft.client.MinecraftClient.getInstance().send(lateLifecycle::finish);
        } catch (RuntimeException | LinkageError schedulingFailure) {
            lateLifecycle.stopSchedulingFailed(schedulingFailure);
        }
    }
}
