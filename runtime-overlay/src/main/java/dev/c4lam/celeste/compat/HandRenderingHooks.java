package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.celestebeta.host.VisualHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.joml.Matrix4f;

/** Composes independently authorized placement and swing layers in their original matrix order. */
public final class HandRenderingHooks {
    private HandRenderingHooks() { }
    public static void transform(AbstractClientPlayerEntity player, Hand hand, MatrixStack matrices) {
        transform(player, hand, player.getStackInHand(hand), matrices);
    }
    public static void transform(AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || client.world == null) return;
        Matrix4f placement = (Matrix4f) ModuleHooks.invoke("hand_models", "hand.placement", null, player, hand, item);
        if (placement == null) placement = new Matrix4f();
        boolean applied = (boolean) ModuleHooks.invoke("swing_animations", "swing.transform", false,
            player, hand, item, matrices, placement);
        if (!applied) matrices.multiplyPositionMatrix(placement);
    }
    public static ItemDisplayContext displayContext(ItemDisplayContext original) {
        return displayContext(original, ItemStack.EMPTY);
    }
    public static ItemDisplayContext displayContext(ItemDisplayContext original, ItemStack item) {
        ModuleHooks.invoke("swing_animations", "swing.finish", null, original, item);
        return (ItemDisplayContext) ModuleHooks.invoke("hand_models", "hand.context", original, original, item);
    }
    public static VisualHookBridge.HandPass begin() {
        return (VisualHookBridge.HandPass) ModuleHooks.invoke("hand_shaders", "hand.begin", null);
    }
}
