package dev.c4lam.celeste.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Fixed-density coordinate space for Celeste's HUD.
 *
 * <p>Three framebuffer pixels per HUD unit is the established 1440p/3x look.
 * Minecraft's GUI scale only changes the conversion into that space; it never
 * changes a widget's physical size or placement.</p>
 */
public record HudViewport(float width, float height, float renderScale) {
    public static final float PIXELS_PER_UNIT = HudViewportPolicy.PIXELS_PER_UNIT;

    public HudViewport {
        width = finitePositive(width, 1.0F);
        height = finitePositive(height, 1.0F);
        renderScale = finitePositive(renderScale, 1.0F);
    }

    public static HudViewport of(int framebufferWidth, int framebufferHeight, double guiScale) {
        return new HudViewport(
            HudViewportPolicy.logicalExtent(framebufferWidth),
            HudViewportPolicy.logicalExtent(framebufferHeight),
            HudViewportPolicy.renderScale(guiScale)
        );
    }

    public static HudViewport current() {
        MinecraftClient client = MinecraftClient.getInstance();
        Window window = client.getWindow();
        return of(window.getFramebufferWidth(), window.getFramebufferHeight(), window.getScaleFactor());
    }

    public float screenToHud(float value) {
        return (float) HudViewportPolicy.screenToHud(value, renderScale);
    }

    public double screenToHud(double value) {
        return HudViewportPolicy.screenToHud(value, renderScale);
    }

    public float hudToScreen(float value) {
        return HudViewportPolicy.hudToScreen(value, renderScale);
    }

    /** Raw GLFW mouse position to fixed HUD space, including high-DPI windows. */
    public double rawMouseToHud(double raw, int windowPixels, int framebufferPixels) {
        return HudViewportPolicy.rawMouseToHud(raw, windowPixels, framebufferPixels);
    }

    public void apply(MatrixStack matrices) {
        matrices.scale(renderScale, renderScale, 1.0F);
    }

    private static float finitePositive(float value, float fallback) {
        return Float.isFinite(value) && value > 0.0F ? value : fallback;
    }
}
