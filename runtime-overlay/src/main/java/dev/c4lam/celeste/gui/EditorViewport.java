package dev.c4lam.celeste.gui;

/** Keeps the hand editor's controls usable in short or narrow windows. */
final class EditorViewport {
    private EditorViewport() { }

    record Label(float centerX, float textY) { }
    record Labels(Label left, Label right) { }

    static float scale(float preferred, int width, int height) {
        float requested = Float.isFinite(preferred) && preferred > 0 ? preferred : 1;
        return Math.max(.01f, Math.min(requested, Math.min(Math.max(1, width) / 330f,
            Math.max(1, height) / 240f)));
    }

    static float labelCenter(float desired, float labelWidth, int width, float scale) {
        float edge = labelWidth / 2 + 4 * scale;
        return Math.max(edge, Math.min(width - edge, desired));
    }

    static Labels labels(float leftX, float leftY, float leftWidth, float rightX, float rightY,
                         float rightWidth, int width, int height, float scale, boolean preview) {
        leftX = labelCenter(leftX, leftWidth, width, scale);
        rightX = labelCenter(rightX, rightWidth, width, scale);
        float minimum = 74 * scale;
        float maximum = height - (preview ? 76 : 65) * scale;
        leftY = Math.clamp(leftY, minimum, maximum);
        rightY = Math.clamp(rightY, minimum, maximum);
        float separation = 21 * scale;
        if (Math.abs(rightX - leftX) < (leftWidth + rightWidth) / 2 + 4 * scale
            && Math.abs(rightY - leftY) < separation) {
            if (leftY <= rightY) {
                rightY = Math.min(maximum, leftY + separation);
                leftY = Math.min(leftY, rightY - separation);
            } else {
                leftY = Math.min(maximum, rightY + separation);
                rightY = Math.min(rightY, leftY - separation);
            }
        }
        return new Labels(new Label(leftX, leftY), new Label(rightX, rightY));
    }
}
