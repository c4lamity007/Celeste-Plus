package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.StorageEspFeature;
import dev.c4lam.celeste.module.impl.visual.StorageBlockEspFeature;
import dev.c4lam.celeste.render.ModuleBoxRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Loaded only from this module's independently verified artifact. */
public final class StorageEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new StorageEspFeature();
        feature.initialize();
        ModuleBoxRenderer.attach(services, feature::extract);
        services.hook("world.block_changed", args -> {
            StorageBlockEspFeature.blockChanged((ClientWorld) args[0], (BlockPos) args[1]);
            return null;
        });
        return feature;
    }
}
