package dev.c4lam.celeste.beta.lifecycle;

import dev.celestebeta.host.DetachableCallbacks;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.SynchronousResourceReloader;
import net.minecraft.util.Identifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Core late-runtime subscriptions. Fabric retains game-owned proxies after this payload closes. */
public final class BetaCoreCallbacks implements AutoCloseable {
    private final DetachableCallbacks.Registration fonts;
    private final DetachableCallbacks.Registration ticks;

    private BetaCoreCallbacks(DetachableCallbacks.Registration fonts, DetachableCallbacks.Registration ticks) {
        this.fonts = fonts;
        this.ticks = ticks;
    }

    public static BetaCoreCallbacks connect(SynchronousResourceReloader fontListener, ClientTickEvents.EndTick tickListener) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) throw new IllegalStateException("Connect core callbacks on the client thread");
        DetachableCallbacks.assertVisibleFrom(BetaCoreCallbacks.class.getClassLoader());
        DetachableCallbacks.assertVisibleFrom(SynchronousResourceReloader.class.getClassLoader());
        DetachableCallbacks.assertVisibleFrom(ClientTickEvents.EndTick.class.getClassLoader());
        ResourceLoader resources = ResourceLoader.get(ResourceType.CLIENT_RESOURCES);
        DetachableCallbacks.Registration fonts = DetachableCallbacks.connect(resources, "celeste:fonts",
            SynchronousResourceReloader.class, fontListener,
            proxy -> resources.registerReloader(Identifier.of("celeste", "fonts"), proxy));
        try {
            DetachableCallbacks.Registration ticks = DetachableCallbacks.connect(ClientTickEvents.END_CLIENT_TICK,
                "celeste:menu", ClientTickEvents.EndTick.class, tickListener, ClientTickEvents.END_CLIENT_TICK::register);
            return new BetaCoreCallbacks(fonts, ticks);
        } catch (RuntimeException | Error failure) {
            fonts.close();
            throw failure;
        }
    }

    @Override public void close() {
        ticks.close();
        fonts.close();
    }

    /** Does not cancel reload work; the late host must wait outside callbacks before releasing the payload. */
    public CompletionStage<Void> detached() {
        return CompletableFuture.allOf(fonts.detached().toCompletableFuture(), ticks.detached().toCompletableFuture())
            .minimalCompletionStage();
    }
}
