package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.SwingAnimationFeature;

public final class SwingAnimationsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new SwingAnimationFeature();
        services.hook("swing.transform", args -> {
            SwingAnimationFeature.transform((net.minecraft.client.network.AbstractClientPlayerEntity) args[0],
                (net.minecraft.util.Hand) args[1], (net.minecraft.item.ItemStack) args[2],
                (net.minecraft.client.util.math.MatrixStack) args[3], (org.joml.Matrix4f) args[4]);
            return Boolean.TRUE;
        });
        services.hook("swing.finish", args -> {
            SwingAnimationFeature.finish((net.minecraft.item.ItemDisplayContext) args[0], (net.minecraft.item.ItemStack) args[1]);
            return null;
        });
        return feature;
    }
}
