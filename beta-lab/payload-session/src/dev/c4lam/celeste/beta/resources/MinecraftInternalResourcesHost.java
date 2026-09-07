package dev.c4lam.celeste.beta.resources;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;
public final class MinecraftInternalResourcesHost {
    public static CompletableFuture<Void> activation, removal;
    public static int removals, closes;
    private final InternalMemoryResources assets;
    private MinecraftInternalResourcesHost(InternalMemoryResources assets) { this.assets = assets; }
    public static MinecraftInternalResourcesHost connect(MinecraftClient client, InternalMemoryResources assets) { return new MinecraftInternalResourcesHost(assets); }
    public CompletableFuture<Void> activateAndReload() { return activation; }
    public CompletableFuture<Void> deactivateAndReload() {
        if (dev.c4lam.celeste.compat.BetaRenderPipelines.active) {
            throw new IllegalStateException("Cannot reload without sources while Celeste pipelines remain registered");
        }
        removals++; return removal;
    }
    public CompletableFuture<Void> retireWhenIdle(Runnable beforeRemoval) {
        beforeRemoval.run();
        return deactivateAndReload().thenRun(this::close);
    }
    public void close() { closes++; assets.close(); }
}
