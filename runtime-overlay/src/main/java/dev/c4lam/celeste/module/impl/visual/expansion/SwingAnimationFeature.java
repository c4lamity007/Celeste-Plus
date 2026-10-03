package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.Items;
import net.minecraft.util.SwingAnimationType;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.joml.Matrix4f;

/** Local first-person presets; item use and special held-item animations stay vanilla. */
public final class SwingAnimationFeature implements ModuleFeature {
    private static boolean enabled;
    private static String style = "Smooth";
    private static double strength = 40;
    private static boolean affectPosition = true;
    private static Pending pending;

    private record Pending(MatrixStack matrices, MatrixStack.Entry entry, MatrixStack.Entry base,
                           ItemStack item, ItemDisplayContext context, Matrix4f pose) { }


    @Override public String name() { return "Swing Animations"; }
    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; pending = null; }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; pending = null; }

    private static void update(ModuleContext context) {
        style = switch (context.mode("Style", "Smooth")) {
            case "Slice" -> "Slice"; case "Sweep" -> "Sweep"; case "Thrust" -> "Thrust";
            case "Spin" -> "Spin"; default -> "Smooth";
        };
        double value = context.slider("Strength", 40);
        strength = Double.isFinite(value) ? Math.clamp(value, 0, 100) : 40;
        affectPosition = context.toggle("Affect position", true);
    }

    public static void transform(AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices) {
        transform(player, hand, item, matrices, new Matrix4f());
    }

    /** Capture inside vanilla's hand push; finish at the existing model-submission hook. */
    public static void transform(AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices,
                          Matrix4f placement) {
        pending = null;
        MinecraftClient client = MinecraftClient.getInstance();
        Hand swingingHand = player.preferredHand == null ? Hand.MAIN_HAND : player.preferredHand;
        if (!enabled || player != client.player || client.world == null || client.currentScreen instanceof dev.c4lam.celeste.gui.HandModelsEditorScreen
            || player.isUsingItem() || player.isUsingSpyglass() || player.isUsingRiptide()
            || item.isEmpty() || item.contains(DataComponentTypes.MAP_ID)
            || item.isOf(Items.CROSSBOW) || item.getSwingAnimation().type() != SwingAnimationType.WHACK
            || (affectPosition ? hand != Hand.MAIN_HAND : swingingHand != hand)) {
            matrices.multiplyPositionMatrix(placement);
            return;
        }
        float progress = player.getHandSwingProgress(client.getRenderTickCounter().getTickProgress(true));
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        if (hand != swingingHand) progress = 0;
        if (affectPosition) {
            // The loader already calls displayContext immediately before submitting the item.
            // Finish there so vanilla cannot add its swing/equip transform over this preset.
            // The captured entry includes camera bob/tilt and is restored with its normal state.
            MatrixStack.Entry entry = matrices.peek();
            pending = new Pending(matrices, entry, entry.copy(), item,
                arm == Arm.RIGHT ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                new Matrix4f(placement).mul(SwingAnimationPose.preset(style, arm == Arm.RIGHT, progress, strength)));
            matrices.multiplyPositionMatrix(placement);
        } else {
            matrices.multiplyPositionMatrix(SwingAnimationPose.compose(placement, style, arm == Arm.RIGHT,
                progress, strength, false));
        }
    }

    /** Consumed once, by the same visible item and the same pushed first-person entry. */
    public static void finish(ItemDisplayContext context, ItemStack item) {
        Pending pass = pending;
        pending = null;
        if (pass == null || !enabled || !affectPosition || pass.context != context || pass.item != item
            || pass.matrices.peek() != pass.entry) return;
        pass.entry.copy(pass.base);
        pass.matrices.multiplyPositionMatrix(pass.pose);
    }
}
