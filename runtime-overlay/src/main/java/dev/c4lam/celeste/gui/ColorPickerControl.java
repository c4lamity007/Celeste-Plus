package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.render.RenderUtil;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.Color;

/** HSV popup and swatch for GuiSetting.ColorPick. */
public final class ColorPickerControl {
    private static final Color ACCENT = new Color(255, 43, 143, 255);
    private static final Color CONTROL = new Color(19, 13, 22, 205);
    private static final Color PANEL = new Color(19, 13, 22, 235);
    private static final float SWATCH = 16.0f;
    private static final float SV = 88.0f;
    private static final float HUE_W = 10.0f;
    private static final float PAD = 8.0f;
    private static final float GAP = 6.0f;

    private GuiSetting.ColorPick open;
    private boolean draggingSv;
    private boolean draggingHue;
    private float swatchX;
    private float swatchY;
    private float popupX;
    private float popupY;
    private float popupW;
    private float popupH;
    private float svX;
    private float svY;
    private float hueX;
    private float hueY;
    private float hue;
    private float sat;
    private float val;
    private float viewportX;
    private float viewportY;
    private float viewportWidth = Float.POSITIVE_INFINITY;
    private float viewportHeight = Float.POSITIVE_INFINITY;

    public void setViewport(float x, float y, float width, float height) {
        viewportX = x;
        viewportY = y;
        viewportWidth = width;
        viewportHeight = height;
        if (open != null) layoutPopup();
    }

    public void drawSwatch(MatrixStack matrices, GuiSetting.ColorPick pick, float x, float y, float animation) {
        Color rim = withAlpha(CONTROL, Math.round(CONTROL.getAlpha() * animation));
        Color fill = new Color(pick.red(), pick.green(), pick.blue(), Math.round(255 * animation));
        RenderUtil.RECT.draw(matrices, x, y, SWATCH, SWATCH, 5.0f, rim);
        RenderUtil.RECT.draw(matrices, x + 1.5f, y + 1.5f, SWATCH - 3.0f, SWATCH - 3.0f, 4.0f, fill);
        if (open == pick) {
            swatchX = x;
            swatchY = y;
            layoutPopup();
        }
    }

    public void drawPopup(MatrixStack matrices) {
        if (open == null) {
            return;
        }
        layoutPopup();
        RenderUtil.RECT.draw(matrices, popupX, popupY, popupW, popupH, 5.0f, PANEL);
        RenderUtil.RECT.draw(matrices, popupX, popupY, popupW, popupH, 5.0f, new Color(255, 43, 143, 28));

        Color hueColor = Color.getHSBColor(hue, 1.0f, 1.0f);
        RenderUtil.GRADIENT_RECT.draw(
            matrices,
            svX,
            svY,
            SV,
            SV,
            5.0f,
            Color.WHITE,
            hueColor,
            Color.BLACK,
            Color.BLACK
        );

        int hueSteps = 18;
        float slice = SV / hueSteps;
        for (int index = 0; index < hueSteps; index++) {
            float t0 = index / (float) hueSteps;
            float t1 = (index + 1) / (float) hueSteps;
            Color top = Color.getHSBColor(t0, 1.0f, 1.0f);
            Color bottom = Color.getHSBColor(t1, 1.0f, 1.0f);
            RenderUtil.GRADIENT_RECT.draw(
                matrices,
                hueX,
                hueY + slice * index,
                HUE_W,
                slice + 0.4f,
                index == 0 ? 3.0f : index == hueSteps - 1 ? 3.0f : 0.0f,
                top,
                top,
                bottom,
                bottom
            );
        }

        float cursorX = svX + sat * SV;
        float cursorY = svY + (1.0f - val) * SV;
        RenderUtil.RECT.draw(matrices, cursorX - 3.5f, cursorY - 3.5f, 7.0f, 7.0f, 3.5f, Color.WHITE);
        RenderUtil.RECT.draw(matrices, cursorX - 2.2f, cursorY - 2.2f, 4.4f, 4.4f, 2.2f, hueColor);
        RenderUtil.RECT.draw(matrices, hueX - 1.5f, hueY + hue * SV - 2.0f, HUE_W + 3.0f, 4.0f, 2.0f, ACCENT);
    }

    public void toggle(GuiSetting.ColorPick pick, float swatchX, float swatchY) {
        if (open == pick) {
            close();
            return;
        }
        open = pick;
        this.swatchX = swatchX;
        this.swatchY = swatchY;
        readHsv();
        layoutPopup();
    }

    public void close() {
        open = null;
        draggingSv = false;
        draggingHue = false;
    }

    public boolean isOpen() {
        return open != null;
    }

    public boolean isDragging() {
        return draggingSv || draggingHue;
    }

    public boolean popupContains(double mouseX, double mouseY) {
        return open != null && contains(mouseX, mouseY, popupX, popupY, popupW, popupH);
    }

    public boolean mouseClicked(double mouseX, double mouseY) {
        if (open == null) {
            return false;
        }
        layoutPopup();
        if (contains(mouseX, mouseY, svX, svY, SV, SV)) {
            draggingSv = true;
            draggingHue = false;
            applySv(mouseX, mouseY);
            return true;
        }
        if (contains(mouseX, mouseY, hueX - 2.0f, hueY, HUE_W + 4.0f, SV)) {
            draggingHue = true;
            draggingSv = false;
            applyHue(mouseY);
            return true;
        }
        return popupContains(mouseX, mouseY);
    }

    public void mouseReleased(double mouseX, double mouseY) {
        if (isDragging()) {
            drag(mouseX, mouseY);
        }
        draggingSv = false;
        draggingHue = false;
    }

    public void drag(double mouseX, double mouseY) {
        if (open == null) {
            return;
        }
        if (draggingSv) {
            applySv(mouseX, mouseY);
        } else if (draggingHue) {
            applyHue(mouseY);
        }
    }

    private void applySv(double mouseX, double mouseY) {
        sat = clamp((float) ((mouseX - svX) / SV));
        val = clamp(1.0f - (float) ((mouseY - svY) / SV));
        writeRgb();
    }

    private void applyHue(double mouseY) {
        hue = clamp((float) ((mouseY - hueY) / SV));
        writeRgb();
    }

    private void writeRgb() {
        if (open == null) {
            return;
        }
        int packed = Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
        open.setRgb(packed);
    }

    private void readHsv() {
        float[] hsv = Color.RGBtoHSB(open.red(), open.green(), open.blue(), null);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
    }

    private void layoutPopup() {
        popupW = PAD + SV + GAP + HUE_W + PAD;
        popupH = PAD + SV + PAD;
        PopupPlacement.Position position = PopupPlacement.place(swatchX, swatchY, SWATCH, SWATCH,
            popupW, popupH, viewportX, viewportY, viewportWidth, viewportHeight);
        popupX = position.x();
        popupY = position.y();
        svX = popupX + PAD;
        svY = popupY + PAD;
        hueX = svX + SV + GAP;
        hueY = svY;
    }

    private static boolean contains(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }
}
