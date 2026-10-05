package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.expansion.KeepSprintFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class KeepSprintEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new KeepSprintFeature();
        services.hook("sprint.after_attack", args -> { KeepSprintFeature.afterAttack((net.minecraft.client.MinecraftClient) args[0], (net.minecraft.entity.Entity) args[1], (Boolean) args[2]); return null; });
        return feature;
    }
}
