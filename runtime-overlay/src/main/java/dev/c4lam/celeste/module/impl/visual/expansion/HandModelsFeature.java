package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.gui.HandModelsEditorScreen;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import org.joml.Quaternionfc;

/** First-person presentation only: vanilla still owns held models, use poses and swings. */
public final class HandModelsFeature implements ModuleFeature {
    private static boolean enabled;
    private static double rightX = 0.72, rightY = 0.78, leftX = 0.28, leftY = 0.78;
    private static double previewRightX = Double.NaN, previewRightY = Double.NaN;
    private static double previewLeftX = Double.NaN, previewLeftY = Double.NaN;
    private static double rightSize = 1, leftSize = 1;
    private static double previewRightSize = Double.NaN, previewLeftSize = Double.NaN;

    @Override public String name() { return "Hand Models"; }
    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; HandModelsFacing.clear(); }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; clearPreview(); }

    private static void update(ModuleContext context) {
        rightX = coordinate(context.slider("Right X", 0.72), 0.72);
        rightY = coordinate(context.slider("Right Y", 0.78), 0.78);
        leftX = coordinate(context.slider("Left X", 0.28), 0.28);
        leftY = coordinate(context.slider("Left Y", 0.78), 0.78);
        rightSize = HandModelsLayout.size(context.slider("Right Size", 1));
        leftSize = HandModelsLayout.size(context.slider("Left Size", 1));
    }

    public static boolean isEditing() {
        return MinecraftClient.getInstance().currentScreen instanceof HandModelsEditorScreen;
    }

    /** GUI preview is immediate even while the module is disabled or the world is paused. */
    public static void setPreviewPosition(boolean right, double x, double y) {
        if (right) {
            previewRightX = coordinate(x, 0.72);
            previewRightY = coordinate(y, 0.78);
        } else {
            previewLeftX = coordinate(x, 0.28);
            previewLeftY = coordinate(y, 0.78);
        }
    }

    public static void clearPreview() {
        previewRightX = previewRightY = previewLeftX = previewLeftY = Double.NaN;
        previewRightSize = previewLeftSize = Double.NaN;
        HandModelsFacing.clear();
    }

    public static void setPreviewSize(boolean right, double size) {
        if (right) previewRightSize = HandModelsLayout.size(size);
        else previewLeftSize = HandModelsLayout.size(size);
    }

    /** Only first-person display transforms change; inventory, third-person and physical hands do not. */
    public static ItemDisplayContext displayContext(ItemDisplayContext original) {
        return displayContext(original, ItemStack.EMPTY);
    }

    public static ItemDisplayContext displayContext(ItemDisplayContext original, ItemStack renderedItem) {
        // Facing now follows the selected model's actual authored transform.
        // Retain physical context for every item: switching it at screen center
        // changes translations and even the basis of generated/left-hand items.
        return original;
    }

    /** Called after vanilla's per-hand push, so vanilla's matching pop restores all state. */
    public static void transform(AbstractClientPlayerEntity player, Hand hand, MatrixStack matrices) {
        transform(player, hand, player.getStackInHand(hand), matrices);
    }

    /** Use the rendered stack: equip transitions may still draw the previous hotbar item. */
    public static void transform(AbstractClientPlayerEntity player, Hand hand, ItemStack renderedItem, MatrixStack matrices) {
        MinecraftClient client = MinecraftClient.getInstance();
        boolean editing = isEditing();
        if (player != client.player || client.world == null) return;
        if (enabled || editing) applyPlacement(player, hand, renderedItem, matrices, client, editing);
        SwingAnimationFeature.transform(player, hand, renderedItem, matrices);
    }

    private static void applyPlacement(AbstractClientPlayerEntity player, Hand hand, ItemStack renderedItem,
                                       MatrixStack matrices, MinecraftClient client, boolean editing) {
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        boolean right = arm == Arm.RIGHT;
        double x = right ? rightX : leftX;
        double y = right ? rightY : leftY;
        double size = right ? rightSize : leftSize;
        if (editing) {
            double px = right ? previewRightX : previewLeftX;
            double py = right ? previewRightY : previewLeftY;
            if (Double.isFinite(px)) x = px;
            if (Double.isFinite(py)) y = py;
            double previewSize = right ? previewRightSize : previewLeftSize;
            if (Double.isFinite(previewSize)) size = previewSize;
        }
        double aspect = (double) Math.max(1, client.getWindow().getFramebufferWidth())
            / Math.max(1, client.getWindow().getFramebufferHeight());
        // Vanilla uses a fixed 70 degree hand FOV, independent of the world FOV slider.
        // Keep its underwater reduction; neither the camera nor options are mutated.
        double fov = 70.0;
        CameraSubmersionType submersion = client.gameRenderer.getCamera().getSubmersionType();
        if (submersion == CameraSubmersionType.WATER || submersion == CameraSubmersionType.LAVA) {
            fov *= 1.0 + (0.85714287 - 1.0) * client.options.getFovEffectScale().getValue();
        }
        // Anchor is the actual grip pivot, not a screen-space inventory icon. Rotating
        // about it preserves per-item first-person models and vanilla pose animation.
        // 1.21.11 also recalculates normals for uniform scale. All scales are
        // positive; vanilla display contexts handle facing without reversing winding.
        ItemDisplayContext physicalContext = right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        Quaternionfc facing = HandModelsFacing.correction(renderedItem, physicalContext, player);
        // Flat generated/handheld models are corrected from their actual model
        // transforms, not a sword-only tag. True 3D/special models retain theirs.
        boolean swordLean = facing != null && renderedItem.isIn(ItemTags.SWORDS);
        matrices.multiplyPositionMatrix(HandModelsPose.transform(right, x, y, aspect, fov, size, facing, swordLean));
    }

    private static double coordinate(double value, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, 0.08, 0.92) : fallback;
    }
}
