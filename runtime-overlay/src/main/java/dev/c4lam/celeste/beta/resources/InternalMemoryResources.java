package dev.c4lam.celeste.beta.resources;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.PackResourceMetadata;
import net.minecraft.text.Text;
import net.minecraft.util.dynamic.Range;

/**
 * Internal client reload input for Celeste assets, never a selectable pack.
 *
 * <p>This class does not implement a pack provider, create/register profiles,
 * touch the ResourcePackManager, or filter/hide any chooser entries. A host
 * reload-input hook must call {@link #augmentReloadPacks(List)} after ordinary
 * selected packs are collected and before the resource manager consumes them.
 * MinecraftInternalResourcesHost supplies the lifecycle adapter; the beta
 * bootstrap must enable the prepared reload hooks before using that host.
 *
 * <p>Only previously verified assets/celeste/** and optional root pack.png are
 * accepted. Integrity, authorization and archive validation remain the caller's
 * responsibility. No resources are downloaded, extracted or written to disk.
 */
public final class InternalMemoryResources implements AutoCloseable {
    /** Diagnostic identity only; never used as a ResourcePackProfile ID. */
    public static final String RESOURCE_ID = "celeste:owner_beta_internal";
    private final MemoryResourceBytes store;
    private final ResourcePackInfo info;
    private boolean assetsInstalled;
    private boolean active;
    private boolean closed;
    private boolean reloadInFlight;

    /** Creates an empty dormant layer after normal game version/registry bootstrap. */
    public InternalMemoryResources() {
        store = new MemoryResourceBytes(metadataBytes());
        info = new ResourcePackInfo(RESOURCE_ID, Text.literal("Celeste internal assets"),
                ResourcePackSource.BUILTIN, Optional.empty());
    }

    public InternalMemoryResources(Map<String, byte[]> verifiedAssets) {
        this();
        installVerifiedAssets(verifiedAssets);
    }

    /**
     * One defensive snapshot, before activation. Do not mutate input while this
     * call runs. Failed validation publishes nothing and permits corrected retry.
     * Successful installation is one-shot, including across deactivation.
     */
    public synchronized void installVerifiedAssets(Map<String, byte[]> verifiedAssets) {
        if (closed || assetsInstalled || reloadInFlight || active) {
            throw new IllegalStateException("Assets require an open, empty, inactive layer with no reload in flight");
        }
        store.install(verifiedAssets);
        assetsInstalled = true;
    }

    /**
     * The host's reload-input hook calls this with the ORIGINAL selected pack
     * list, once per actual reload (including later user-triggered reloads).
     *
     * <p>Inactive/closed returns that exact input list. Active returns an immutable
     * shallow copy with an independent Celeste resource view appended, preserving
     * the order and identity of every other pack. No input list/pack is mutated,
     * closed, replaced or removed. The normal resource manager owns and closes
     * the returned view. Reapplying this same layer to its augmented list does
     * not duplicate an existing open view. Never cache an augmented list as the
     * original selected list for future reloads.
     */
    public synchronized List<ResourcePack> augmentReloadPacks(List<ResourcePack> selectedPacks) {
        Objects.requireNonNull(selectedPacks, "selectedPacks");
        if (!active || closed) {
            return selectedPacks;
        }
        for (ResourcePack pack : selectedPacks) {
            Objects.requireNonNull(pack, "selected pack");
            if (pack instanceof MemoryResourcePack memory && memory.belongsTo(store)) {
                return selectedPacks;
            }
        }
        List<ResourcePack> augmented = new ArrayList<>(selectedPacks.size() + 1);
        augmented.addAll(selectedPacks);
        augmented.add(new MemoryResourcePack(info, store));
        return List.copyOf(augmented);
    }

    /**
     * Activate for reload inputs, then request the host's normal full reload.
     * Call on the client thread and await success before consuming assets.
     * The host supplier must observe BOTH success and failure and settle only
     * after the client result callback and any cleanup replacement finish. This
     * retires old resource views; already opened streams may still finish.
     * Directly passing
     * MinecraftClient::reloadResources is insufficient in 1.21.11: its future
     * may remain pending on failure. MinecraftInternalResourcesHost observes the
     * prepared callback hooks instead of relying on that raw future alone.
     */
    public CompletableFuture<Void> activateAndReload(Supplier<CompletableFuture<Void>> reloadResources) {
        return changeAndReload(reloadResources, true);
    }

    /** Excludes this layer from future reload inputs; retains old views' bytes until close. */
    public CompletableFuture<Void> deactivateAndReload(Supplier<CompletableFuture<Void>> reloadResources) {
        return changeAndReload(reloadResources, false);
    }

    private synchronized CompletableFuture<Void> changeAndReload(
            Supplier<CompletableFuture<Void>> reloadResources, boolean enabled) {
        Objects.requireNonNull(reloadResources, "reloadResources");
        if (closed || reloadInFlight) {
            throw new IllegalStateException(closed ? "Internal resources are closed" : "Resource reload is in flight");
        }
        if (enabled && !assetsInstalled) {
            throw new IllegalStateException("Install verified assets before activating internal resources");
        }
        boolean previous = active;
        active = enabled;
        CompletableFuture<Void> reload;
        try {
            reloadInFlight = true;
            reload = Objects.requireNonNull(reloadResources.get(), "reload future");
        } catch (RuntimeException | Error failure) {
            reloadInFlight = false;
            active = previous;
            throw failure;
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        reload.whenComplete((unused, failure) -> {
            synchronized (InternalMemoryResources.this) {
                reloadInFlight = false;
            }
            // Retain bytes/requested state on async failure for explicit retry.
            if (failure == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(failure);
            }
        });
        // Observer cancellation cannot suppress bookkeeping or cancel the real reload.
        return result;
    }

    public synchronized boolean isActive() {
        return active && !closed;
    }

    public long retainedAssetBytes() {
        return store.retainedAssetBytes();
    }

    /**
     * Shutdown-only, or after successful deactivation/full reload. Invalidates
     * views/unopened suppliers and clears shared references, not external loaded
     * textures/shaders. Open streams may finish until closed/GC'd. No secure
     * JVM/GPU/OS memory-erasure claim. Other resource packs are never closed here.
     */
    @Override
    public synchronized void close() {
        if (reloadInFlight) {
            throw new IllegalStateException("Wait for the resource reload before closing its bytes");
        }
        closed = true;
        active = false;
        store.close();
    }

    private static byte[] metadataBytes() {
        PackResourceMetadata metadata = new PackResourceMetadata(Text.literal("Celeste verified internal assets"),
                new Range<>(SharedConstants.getGameVersion().packVersion(ResourceType.CLIENT_RESOURCES)));
        JsonObject root = new JsonObject();
        root.add("pack", PackResourceMetadata.CLIENT_RESOURCES_SERIALIZER.codec()
                .encodeStart(JsonOps.INSTANCE, metadata).getOrThrow());
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }
}
