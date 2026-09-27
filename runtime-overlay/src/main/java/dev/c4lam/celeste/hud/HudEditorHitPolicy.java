package dev.c4lam.celeste.hud;

/** Corner targets retain an outside margin without consuming small cards' bodies. */
final class HudEditorHitPolicy {
    private HudEditorHitPolicy() { }

    /** NW=0, NE=1, SW=2, SE=3; -1 leaves the pointer available for body dragging. */
    static int corner(float x, float y, float width, float height,
                      double mouseX, double mouseY, float margin) {
        if (!(width > 0.0F && height > 0.0F && margin > 0.0F)
            || !Float.isFinite(x) || !Float.isFinite(y)
            || !Float.isFinite(width) || !Float.isFinite(height)
            || !Double.isFinite(mouseX) || !Double.isFinite(mouseY)) return -1;
        float insetX = Math.min(margin, width * 0.25F);
        float insetY = Math.min(margin, height * 0.25F);
        boolean left = mouseX >= x - margin && mouseX <= x + insetX;
        boolean right = mouseX >= x + width - insetX && mouseX <= x + width + margin;
        boolean top = mouseY >= y - margin && mouseY <= y + insetY;
        boolean bottom = mouseY >= y + height - insetY && mouseY <= y + height + margin;
        if (left && top) return 0;
        if (right && top) return 1;
        if (left && bottom) return 2;
        if (right && bottom) return 3;
        return -1;
    }
}
