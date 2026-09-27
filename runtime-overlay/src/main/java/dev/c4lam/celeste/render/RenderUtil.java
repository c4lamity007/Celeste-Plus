package dev.c4lam.celeste.render;

import net.minecraft.client.util.math.MatrixStack;

/** Stable facade consumed by Celeste GUI screens and widgets. */
public final class RenderUtil {
    public static final RectRender RECT = new RectRender();
    public static final GradientRectRender GRADIENT_RECT = new GradientRectRender();
    public static final SelectionOutlineRender SELECTION_OUTLINE = new SelectionOutlineRender();
    public static final CircularCountdownRender CIRCULAR_COUNTDOWN = new CircularCountdownRender();
    public static final TextureRectRender TEXTURE_RECT = new TextureRectRender();
    public static final BlurRectRender BLUR_RECT = new BlurRectRender();

    private RenderUtil() {
    }

    public static MatrixStack matrices() {
        return GuiRenderer.getInstance().matrices();
    }

    /** Captures the current screen only when a caller explicitly requests it. */
    public static boolean captureBlur() {
        return KawaseBlurProgram.getInstance().capture();
    }

    /** Releases screen-sized blur targets when no glass screen is visible. */
    public static void releaseBlur() {
        KawaseBlurProgram.getInstance().cleanup();
    }

    /** Releases long-lived renderer resources during client shutdown. */
    public static void cleanup() {
        KawaseBlurProgram.getInstance().close();
        GuiRenderer.getInstance().close();
    }
}
