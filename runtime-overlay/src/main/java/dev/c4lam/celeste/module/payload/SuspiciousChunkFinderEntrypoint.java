package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.SuspiciousChunkFinderFeature;
import dev.c4lam.celeste.module.impl.visual.SuspiciousChunkFinderFeature;
import dev.c4lam.celeste.render.ModuleBoxRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Loaded only from this module's independently verified artifact. */
public final class SuspiciousChunkFinderEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new SuspiciousChunkFinderFeature();
        var drained = new java.util.concurrent.CompletableFuture<Void>();
        services.scope().own(() -> feature.retire().whenComplete((ignored, failure) -> {
            if (failure == null) drained.complete(null); else drained.completeExceptionally(failure);
        }), drained);
        feature.initialize();
        ModuleBoxRenderer.attach(services, feature::extract);
        services.hook("world.block_changed", args -> {
            SuspiciousChunkFinderFeature.blockChanged((ClientWorld) args[0], (BlockPos) args[1]);
            return null;
        });
        return feature;
    }
}
