package dev.c4lam.celeste.module.payload;

import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.movement.FreecamFeature;

/** This factory and its implementation belong to one verified module artifact. */
public final class FreecamEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new FreecamFeature();
        services.hook("freecam.detached", args -> { return FreecamFeature.isDetached((net.minecraft.client.MinecraftClient) args[0]); });
        services.hook("freecam.control", args -> { return FreecamFeature.keepsLocalPlayerControl((net.minecraft.client.network.ClientPlayerEntity) args[0]); });
        services.hook("freecam.focus", args -> { return FreecamFeature.renderedCameraFocus((net.minecraft.entity.Entity) args[0]); });
        services.hook("freecam.look", args -> { return FreecamFeature.redirectPlayerLook((net.minecraft.entity.Entity) args[0], ((Number) args[1]).doubleValue(), ((Number) args[2]).doubleValue()); });
        services.hook("freecam.input", args -> { return dev.c4lam.celeste.module.impl.movement.FreecamInputPolicy.correction(FreecamFeature.blocksPlayerInput((net.minecraft.client.input.Input) args[0]), (net.minecraft.util.math.Vec2f) args[1], (net.minecraft.util.PlayerInput) args[2]); });
        services.hook("freecam.toggle", args -> { return feature.toggleFlight((net.minecraft.client.MinecraftClient) args[0]); });
        return feature;
    }
}
