package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.HandModelsFeature;

public final class HandModelsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new HandModelsFeature();
        services.hook("hand.placement", args -> HandModelsFeature.placement(
            (net.minecraft.client.network.AbstractClientPlayerEntity) args[0],
            (net.minecraft.util.Hand) args[1], (net.minecraft.item.ItemStack) args[2]));
        services.hook("hand.context", args -> HandModelsFeature.displayContext(
            (net.minecraft.item.ItemDisplayContext) args[0], (net.minecraft.item.ItemStack) args[1]));
        return feature;
    }
}
