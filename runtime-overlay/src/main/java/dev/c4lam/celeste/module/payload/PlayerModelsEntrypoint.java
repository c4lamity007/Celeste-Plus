package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class PlayerModelsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new PlayerModelsFeature();
        services.hook("model.capture", args -> { PlayerModelsFeature.capture((net.minecraft.entity.LivingEntity) args[0], (net.minecraft.client.render.entity.state.LivingEntityRenderState) args[1]); return null; });
        services.hook("model.replace", args -> { return PlayerModelsFeature.replaceVanilla((net.minecraft.client.render.entity.state.LivingEntityRenderState) args[0]); });
        services.hook("model.attack", args -> { PlayerModelsFeature.recordAttack((net.minecraft.client.MinecraftClient) args[0], (net.minecraft.entity.Entity) args[1]); return null; });
        return feature;
    }
}
