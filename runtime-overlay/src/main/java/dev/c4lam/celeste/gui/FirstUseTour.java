package dev.c4lam.celeste.gui;

import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.util.math.MatrixStack;
import org.lwjgl.glfw.GLFW;
import java.awt.Color;

/** Small coach marks over the real controls, with no duplicated sample controls. */
public final class FirstUseTour {
    public enum Action { NONE, HANDLED, NEXT, BACK, DISMISS }
    public record Rect(float x, float y, float width, float height) {
        public boolean contains(double mx, double my) {
            return mx >= x && my >= y && mx < x + width && my < y + height;
        }
        boolean overlaps(Rect r) {
            return r != null && x < r.x + r.width + 8 && x + width + 8 > r.x
                && y < r.y + r.height + 8 && y + height + 8 > r.y;
        }
    }
    private static final Color TEXT = new Color(246, 242, 247);
    private static final Color MUTED = new Color(171, 162, 175);
    private static final Color ACCENT = new Color(255, 43, 143);
    private static final long FADE_NANOS = 240_000_000L;
    private final FirstUseTourState state = new FirstUseTourState();
    private final long openedAt = System.nanoTime();
    private long changedAt = openedAt, closingAt, lastFrame = openedAt;
    private Rect spotlight;
    private Rect card = new Rect(0, 0, 0, 0), skip = card, next = card, back = card;

    public FirstUseTourState state() { return state; }
    public boolean closing() { return closingAt != 0; }
    public boolean visible() { return closingAt == 0 || System.nanoTime() - closingAt < FADE_NANOS; }
    public void dismiss() { if (!closing()) { closingAt = System.nanoTime(); state.dismiss(); } }
    public void next() { state.next(); changedAt = System.nanoTime(); spotlight = null; }
    public void back() { state.back(); changedAt = System.nanoTime(); spotlight = null; }

    public void draw(MatrixStack m, float sw, float sh, Rect panel, Rect target,
                     double mx, double my, String menuKey) {
        draw(m, sw, sh, panel, target, mx, my, menuKey, false);
    }
    public void draw(MatrixStack m, float sw, float sh, Rect panel, Rect target,
                     double mx, double my, String menuKey, boolean editor) {
        draw(m, sw, sh, panel, target, mx, my, menuKey, editor, target);
    }
    public void draw(MatrixStack m, float sw, float sh, Rect panel, Rect target,
                     double mx, double my, String menuKey, boolean editor, Rect focus) {
        long now = System.nanoTime();
        float dt = Math.max(0, Math.min(.05f, (now - lastFrame) / 1_000_000_000f));
        lastFrame = now;
        float opacity = ease(Math.min(1, (now - openedAt) / (float) FADE_NANOS))
            * (closing() ? 1 - ease(Math.min(1, (now - closingAt) / (float) FADE_NANOS)) : 1);
        float entrance = ease(Math.min(1, (now - changedAt) / 200_000_000f));
        boolean centered = state.step() == FirstUseTourState.Step.WELCOME || state.step() == FirstUseTourState.Step.READY;
        if (focus != null && !centered) {
            float b = 1 - (float) Math.exp(-dt / .075f);
            spotlight = spotlight == null ? focus : new Rect(mix(spotlight.x, focus.x, b), mix(spotlight.y, focus.y, b),
                mix(spotlight.width, focus.width, b), mix(spotlight.height, focus.height, b));
            outline(m, new Rect(spotlight.x - 3, spotlight.y - 3, spotlight.width + 6, spotlight.height + 6), ACCENT, .65f * opacity);
        }
        FirstUseTourLayout.Placement placement = FirstUseTourLayout.place(sw, sh,
            new FirstUseTourLayout.Box(panel.x, panel.y, panel.width, panel.height),
            target == null ? null : new FirstUseTourLayout.Box(target.x, target.y, target.width, target.height), centered, editor);
        if (editor && target != null && FirstUseTourLayout.overlap(placement,
                new FirstUseTourLayout.Box(target.x, target.y, target.width, target.height)) > 0) {
            // On a very small editor the real menu takes priority. Clear every
            // coach hitbox until room returns; Esc still exits to the main GUI.
            card = skip = next = back = new Rect(0, 0, 0, 0);
            return;
        }
        boolean compact = placement.compact();
        float w = placement.width(), h = placement.height();
        float x = placement.x(), y = placement.y();
        card = new Rect(x, y, w, h);
        float a = opacity * entrance;
        if (focus != null && !centered) {
            FirstUseTourLayout.Connector connector = FirstUseTourLayout.connector(placement,
                new FirstUseTourLayout.Box(focus.x, focus.y, focus.width, focus.height));
            if (connector != null) {
                line(m, connector.cardX(), connector.cardY(), connector.elbowX(), connector.elbowY(), a);
                line(m, connector.elbowX(), connector.elbowY(), connector.targetX(), connector.targetY(), a);
                rect(m, connector.targetX() - 1.5f, connector.targetY() - 1.5f, 3, 3, 1.5f, ACCENT, .9f * a);
            }
        }
        rect(m, x, y, w, h, 10, new Color(22, 19, 26), a);
        Fonts.semibold().drawText(m, state.title(), x + (compact ? 12 : 16), y + (compact ? 10 : 16),
            compact ? 10 : 12, alpha(TEXT, a));
        skip = new Rect(x + w - 51, y + (compact ? 4 : 10), 39, 23);
        label(m, "Skip", skip.x + 7, skip.y + 7, 7.5f, skip.contains(mx, my) ? TEXT : MUTED, a);
        String[] lines = state.instruction(editor);
        if (compact) lines = compactLines(lines, w - 24);
        for (int i = 0; i < lines.length; i++) label(m, lines[i], x + (compact ? 12 : 16),
            y + (compact ? 30 : 44) + i * (compact ? 10 : 12), compact ? 7.1f : 8, MUTED, a);
        if (state.step() == FirstUseTourState.Step.READY)
            label(m, "Menu: " + menuKey, x + 16, y + 61, 8, TEXT, a);
        float by = y + h - (compact ? 29 : 39);
        back = new Rect(x + 10, by, 44, compact ? 21 : 26);
        next = new Rect(x + w - (compact ? 70 : 88), by, compact ? 58 : 72, compact ? 21 : 26);
        if (!centered && !compact) {
            label(m, state.number() + " / 6", x + 16, y + 80, 6.7f, MUTED, a);
            if (state.complete()) label(m, "Done", x + w - 37, y + 80, 6.7f, TEXT, a);
        }
        String button = state.step() == FirstUseTourState.Step.WELCOME ? "Start"
            : state.step() == FirstUseTourState.Step.READY ? "Done" : "Next";
        rect(m, next.x, next.y, next.width, next.height, 7, next.contains(mx, my) ? new Color(255, 65, 154) : ACCENT, a);
        Fonts.semibold().drawCenteredText(m, button, next.x + next.width / 2, next.y + (compact ? 6 : 8),
            compact ? 7.3f : 8, alpha(TEXT, a));
        if (state.step() == FirstUseTourState.Step.WELCOME) label(m, "Esc to skip", x + 16, by + 9, 7, MUTED, a);
        else label(m, "Back", back.x + 7, back.y + (compact ? 6 : 8), compact ? 7.3f : 8,
            back.contains(mx, my) ? TEXT : MUTED, a);
    }

    public Action click(double x, double y, int button) {
        if (closing()) return Action.HANDLED;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (skip.contains(x, y)) return Action.DISMISS;
            if (next.contains(x, y)) return state.step() == FirstUseTourState.Step.READY ? Action.DISMISS : Action.NEXT;
            if (state.step() != FirstUseTourState.Step.WELCOME && back.contains(x, y)) return Action.BACK;
        }
        return card.contains(x, y) ? Action.HANDLED : Action.NONE;
    }
    public boolean contains(double x, double y) { return card.contains(x, y); }
    public boolean pointer(double x, double y) {
        return !closing() && (skip.contains(x, y) || next.contains(x, y)
            || state.step() != FirstUseTourState.Step.WELCOME && back.contains(x, y));
    }
    private static String[] compactLines(String[] instructions, float width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String row = "";
        String primary = instructions.length == 0 ? "" : instructions[0];
        for (String word : primary.split(" ")) {
            String next = row.isEmpty() ? word : row + " " + word;
            if (!row.isEmpty() && Fonts.regular().width(next, 7.1f) > width) {
                lines.add(row);
                row = word;
            } else row = next;
        }
        if (!row.isEmpty()) lines.add(row);
        // The primary action is always first; the secondary hint can be omitted
        // on a very narrow coach so the real control and navigation stay usable.
        return lines.stream().limit(2).toArray(String[]::new);
    }
    private static void outline(MatrixStack m, Rect r, Color c, float a) {
        rect(m, r.x, r.y, r.width, 1, .5f, c, a);
        rect(m, r.x, r.y + r.height - 1, r.width, 1, .5f, c, a);
        rect(m, r.x, r.y, 1, r.height, .5f, c, a);
        rect(m, r.x + r.width - 1, r.y, 1, r.height, .5f, c, a);
    }
    private static void line(MatrixStack m, float x0, float y0, float x1, float y1, float a) {
        rect(m, Math.min(x0, x1) - .5f, Math.min(y0, y1) - .5f,
            Math.max(1, Math.abs(x1 - x0)), Math.max(1, Math.abs(y1 - y0)), .5f, ACCENT, .48f * a);
    }
    private static void rect(MatrixStack m, float x, float y, float w, float h, float r, Color c, float a) {
        if (w > 0 && h > 0 && a > 0) RenderUtil.RECT.draw(m, x, y, w, h, r, alpha(c, a));
    }
    private static void label(MatrixStack m, String s, float x, float y, float size, Color c, float a) {
        Fonts.regular().drawText(m, s, x, y, size, alpha(c, a));
    }
    private static Color alpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * Math.max(0, Math.min(1, a)))); }
    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
}
