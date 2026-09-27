package dev.c4lam.celeste.gui;

/** Keeps the coach clear of the control being taught, including the HUD add menu. */
public final class FirstUseTourLayout {
    private static final float MARGIN = 8.0F;
    private static final float CONTROL_GAP = 4.0F;
    public static final float ANCHOR_GAP = 12.0F;
    public record Box(float x, float y, float width, float height) { }
    public record Placement(float x, float y, float width, float height, boolean compact) { }
    /** The middle point can equal an endpoint for a straight leader. */
    public record Connector(float cardX, float cardY, float elbowX, float elbowY, float targetX, float targetY) { }

    private FirstUseTourLayout() { }

    public static Placement place(float screenWidth, float screenHeight, Box panel, Box target,
                                  boolean centered, boolean editor) {
        float sw = Math.max(1.0F, screenWidth), sh = Math.max(1.0F, screenHeight);
        float width = Math.min(292.0F, Math.max(1.0F, sw - MARGIN * 2));
        float height = Math.min(centered ? 148.0F : 138.0F, Math.max(1.0F, sh - MARGIN * 2));
        if (centered) return bounded(panel.x + (panel.width - width) * .5F,
            panel.y + (panel.height - height) * .5F, width, height, false, sw, sh);
        if (target != null) {
            Placement adjacent = adjacent(width, height, false, sw, sh, target);
            if (adjacent != null) return adjacent;
            float compactHeight = Math.min(88.0F, Math.max(1.0F, sh - MARGIN * 2));
            adjacent = adjacent(width, compactHeight, true, sw, sh, target);
            if (adjacent != null) return adjacent;
            float[] sideWidths = {sw - MARGIN - target.x - target.width - ANCHOR_GAP,
                target.x - ANCHOR_GAP - MARGIN, 144.0F};
            for (float sideWidth : sideWidths) {
                if (sideWidth < 144.0F || sideWidth >= width) continue;
                adjacent = adjacent(sideWidth, compactHeight, true, sw, sh, target);
                if (adjacent != null) return adjacent;
            }
        }
        Placement best = choose(width, height, false, sw, sh, panel, target, editor);
        if (overlap(best, target) == 0.0F) return best;

        height = Math.min(88.0F, Math.max(1.0F, sh - MARGIN * 2));
        best = choose(width, height, true, sw, sh, panel, target, editor);
        if (overlap(best, target) == 0.0F || target == null) return best;
        // A narrow side can still hold two short lines and the navigation.
        float[] narrower = {target.x - panel.x - MARGIN * 2 - CONTROL_GAP,
            panel.x + panel.width - target.x - target.width - MARGIN * 2 - CONTROL_GAP, 144.0F};
        for (float available : narrower) {
            if (available < 144.0F || available >= width) continue;
            Placement candidate = choose(available, height, true, sw, sh, panel, target, editor);
            if (overlap(candidate, target) < overlap(best, target)) best = candidate;
            if (overlap(best, target) == 0.0F) return best;
        }
        return best;
    }

    private static Placement adjacent(float width, float height, boolean compact, float sw, float sh, Box target) {
        float centerX = target.x + target.width * .5F, centerY = target.y + target.height * .5F;
        float[] xs = {target.x + target.width + ANCHOR_GAP, target.x - ANCHOR_GAP - width,
            centerX - width * .5F, centerX - width * .5F};
        float[] ys = {centerY - height * .5F, centerY - height * .5F,
            target.y + target.height + ANCHOR_GAP, target.y - ANCHOR_GAP - height};
        Placement best = null;
        float shortest = Float.POSITIVE_INFINITY;
        for (int side = 0; side < 4; side++) {
            // Clamp only along the target edge. Moving toward the target when
            // space runs out would erase the deliberate gap or cover it.
            float fixed = side < 2 ? xs[side] : ys[side];
            float extent = side < 2 ? width : height;
            float screen = side < 2 ? sw : sh;
            if (fixed < MARGIN || fixed + extent > screen - MARGIN) continue;
            Placement candidate = bounded(xs[side], ys[side], width, height, compact, sw, sh);
            if (overlap(candidate, target) > 0) continue;
            Connector leader = connector(candidate, target);
            if (leader == null) continue;
            float distance = Math.abs(leader.cardX - leader.elbowX) + Math.abs(leader.cardY - leader.elbowY)
                + Math.abs(leader.elbowX - leader.targetX) + Math.abs(leader.elbowY - leader.targetY);
            if (distance < shortest) { best = candidate; shortest = distance; }
        }
        return best;
    }

    public static Connector connector(Placement card, Box target) {
        if (target == null || overlap(card, target) > 0) return null;
        float cardRight = card.x + card.width, cardBottom = card.y + card.height;
        float targetRight = target.x + target.width, targetBottom = target.y + target.height;
        float insetX = Math.min(6.0F, card.width * .5F), insetY = Math.min(6.0F, card.height * .5F);
        if (card.x >= targetRight || cardRight <= target.x) {
            float cardX = card.x >= targetRight ? card.x : cardRight;
            float targetX = card.x >= targetRight ? targetRight : target.x;
            float cardY = Math.clamp(target.y + target.height * .5F, card.y + insetY, cardBottom - insetY);
            float targetY = Math.clamp(cardY, target.y, targetBottom);
            return new Connector(cardX, cardY, targetX, cardY, targetX, targetY);
        }
        if (card.y >= targetBottom || cardBottom <= target.y) {
            float cardY = card.y >= targetBottom ? card.y : cardBottom;
            float targetY = card.y >= targetBottom ? targetBottom : target.y;
            float cardX = Math.clamp(target.x + target.width * .5F, card.x + insetX, cardRight - insetX);
            float targetX = Math.clamp(cardX, target.x, targetRight);
            return new Connector(cardX, cardY, cardX, targetY, targetX, targetY);
        }
        return null;
    }

    private static Placement choose(float width, float height, boolean compact, float sw, float sh,
                                    Box panel, Box target, boolean editor) {
        float left = panel.x + MARGIN, right = panel.x + panel.width - width - MARGIN;
        float top = panel.y + MARGIN, bottom = panel.y + panel.height - height - MARGIN;
        float[] xs = {right, left, right, left};
        float[] ys = editor ? new float[]{top, top, bottom, bottom} : new float[]{bottom, bottom, top, top};
        Placement best = null;
        float minimum = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            Placement candidate = bounded(xs[i], ys[i], width, height, compact, sw, sh);
            float score = overlap(candidate, target);
            if (score < minimum) { best = candidate; minimum = score; }
            if (score == 0) break;
        }
        return best;
    }

    private static Placement bounded(float x, float y, float w, float h, boolean compact, float sw, float sh) {
        float mx = Math.min(MARGIN, Math.max(0, (sw - w) * .5F));
        float my = Math.min(MARGIN, Math.max(0, (sh - h) * .5F));
        return new Placement(Math.clamp(x, mx, Math.max(mx, sw - w - mx)),
            Math.clamp(y, my, Math.max(my, sh - h - my)), w, h, compact);
    }

    public static float overlap(Placement card, Box target) {
        if (target == null) return 0;
        float width = Math.max(0, Math.min(card.x + card.width, target.x + target.width + CONTROL_GAP)
            - Math.max(card.x, target.x - CONTROL_GAP));
        float height = Math.max(0, Math.min(card.y + card.height, target.y + target.height + CONTROL_GAP)
            - Math.max(card.y, target.y - CONTROL_GAP));
        return width * height;
    }
}
