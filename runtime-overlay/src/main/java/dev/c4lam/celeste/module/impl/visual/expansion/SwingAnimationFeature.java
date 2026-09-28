package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;

/** Adds a local swing after Hand Models placement; vanilla still owns equip and use animations. */
public final class SwingAnimationFeature implements ModuleFeature {
    private static boolean enabled;
    private static String style = "Smooth";
    private static double strength = 40;
    private static boolean affectPosition = true;

    @Override public String name() { return "Swing Animations"; }
    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; }

    private static void update(ModuleContext context) {
        style = switch (context.mode("Style", "Smooth")) {
            case "Slice" -> "Slice"; case "Sweep" -> "Sweep"; case "Thrust" -> "Thrust";
            case "Spin" -> "Spin"; default -> "Smooth";
        };
        double value = context.slider("Strength", 40);
        strength = Double.isFinite(value) ? Math.clamp(value, 0, 100) : 40;
        affectPosition = context.toggle("Affect position", true);
    }

    static void transform(AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || player != client.player || client.world == null || HandModelsFeature.isEditing()
            || player.isUsingItem() || player.isUsingSpyglass() || player.isUsingRiptide()
            || item.isEmpty() || item.contains(DataComponentTypes.MAP_ID) || player.preferredHand != hand) return;
        float progress = player.getHandSwingProgress(client.getRenderTickCounter().getTickProgress(false));
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        matrices.multiplyPositionMatrix(SwingAnimationPose.transform(style, arm == Arm.RIGHT, progress, strength, affectPosition));
    }
}
