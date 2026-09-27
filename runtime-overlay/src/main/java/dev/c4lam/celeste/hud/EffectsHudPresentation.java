package dev.c4lam.celeste.hud;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One full-color icon strip; expiring icons travel into readable countdown callouts. */
public final class EffectsHudPresentation {
    public static final float TITLE_HEIGHT = 16.0F;
    public static final float TITLE_WIDTH = 48.0F;
    public static final float TITLE_GAP = 0.0F;
    public static final float ICON_SIZE = 11.0F;
    public static final float ICON_PADDING = 5.0F;
    public static final float ICON_GAP = 4.0F;
    public static final float ROW_HEIGHT = 18.0F;
    public static final float WARNING_HEIGHT = 18.0F;
    public static final float WARNING_GAP = 3.0F;
    public static final float WARNING_NAME_X = ICON_PADDING + ICON_SIZE + 5.0F;
    public static final float WARNING_TIMER_GAP = 8.0F;
    public static final float RIGHT_PADDING = 5.0F;
    public static final float MIN_WARNING_WIDTH = WARNING_NAME_X + WARNING_TIMER_GAP + RIGHT_PADDING;
    public static final float MAX_WARNING_WIDTH = 148.0F;
    public static final int WARNING_START_TICKS = 10 * 20;
    private static final float TRANSITION_SECONDS = 0.42F;
    private static final float LIFECYCLE_SECONDS = 0.22F;
    private final Map<String, Motion> motions = new LinkedHashMap<>();
    private float surfaceWidth = TITLE_WIDTH;
    private float surfaceHeight = TITLE_HEIGHT;
    private boolean surfacePositioned;

    public record Effect(String key, int ticks, float nameWidth, float countdownWidth) { }

    /** x/y is the moving callout origin; each effect always has exactly one sprite. */
    public record Item(String key, int ticks, float x, float y, float progress,
                       float cardWidth, float reveal, float iconScale, float opacity) {
        public float iconX() { return x + ICON_PADDING + ICON_SIZE * (1.0F - iconScale) * 0.5F; }
        public float iconY() { return y + (ROW_HEIGHT - ICON_SIZE * iconScale) * 0.5F; }
        public float textAlpha() { return warning(ticks) ? smooth((progress - 0.8F) / 0.2F) : 0.0F; }
    }

    public record Layout(List<Item> items, float stripWidth, float stripHeight,
                         float stripOpacity, float width, float height, int activeCount,
                         float surfaceWidth, float surfaceHeight) { }

    public void reset() {
        motions.clear();
        surfaceWidth = TITLE_WIDTH;
        surfaceHeight = TITLE_HEIGHT;
        surfacePositioned = false;
    }

    public Layout update(List<Effect> effects, float deltaSeconds) {
        return update(effects, deltaSeconds, TITLE_WIDTH);
    }

    public Layout update(List<Effect> effects, float deltaSeconds, float measuredTitleWidth) {
        float dt = Float.isFinite(deltaSeconds) ? Math.clamp(deltaSeconds, 0.0F, 0.1F) : 0.0F;
        Map<String, Effect> active = new LinkedHashMap<>();
        for (Effect effect : effects) active.put(effect.key(), effect);
        for (Effect effect : active.values()) {
            motions.computeIfAbsent(effect.key(), ignored -> new Motion()).effect = effect;
        }
        var iterator = motions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Motion motion = entry.getValue();
            boolean present = active.containsKey(entry.getKey());
            motion.life = Math.clamp(motion.life + (present ? dt : -dt) / LIFECYCLE_SECONDS, 0.0F, 1.0F);
            if (!present && motion.life == 0.0F) iterator.remove();
        }

        float slots = 0.0F;
        for (Motion motion : motions.values()) {
            boolean nextWarning = warning(motion.effect.ticks());
            motion.captureDeparture = nextWarning && !motion.warning;
            motion.warning = nextWarning;
            float direction = nextWarning ? 1.0F : -1.0F;
            motion.phase = Math.clamp(motion.phase + direction * dt / TRANSITION_SECONDS, 0.0F, 1.0F);
            slots += 1.0F - smooth(motion.phase);
        }
        float occupancy = Math.clamp(slots, 0.0F, 1.0F);
        float stripWidth = (2.0F * ICON_PADDING - ICON_GAP) * occupancy
            + (ICON_SIZE + ICON_GAP) * slots;
        float stripHeight = ROW_HEIGHT * occupancy;
        float baseY = TITLE_HEIGHT + TITLE_GAP;
        float desiredWidth = Math.max(Math.max(ICON_PADDING * 2 + ICON_SIZE, safe(measuredTitleWidth)), stripWidth);
        float desiredHeight = TITLE_HEIGHT + stripHeight;
        if (!surfacePositioned) {
            surfaceWidth = desiredWidth;
            surfaceHeight = desiredHeight;
            surfacePositioned = !motions.isEmpty();
        }
        float surfaceFollow = (float) -Math.expm1(-20.0F * dt);
        surfaceWidth += (desiredWidth - surfaceWidth) * surfaceFollow;
        surfaceHeight += (desiredHeight - surfaceHeight) * surfaceFollow;
        float warningY = surfaceHeight + WARNING_GAP;
        float stripOffset = Math.max(0.0F, (surfaceWidth - stripWidth) * 0.5F);
        float compactX = 0.0F;
        float width = surfaceWidth;
        float height = surfaceHeight;
        float warningWidth = MIN_WARNING_WIDTH;
        for (Motion motion : motions.values()) {
            if (motion.phase <= 0.0F) continue;
            Effect effect = motion.effect;
            warningWidth = Math.max(warningWidth, Math.clamp(WARNING_NAME_X + safe(effect.nameWidth())
                + WARNING_TIMER_GAP + safe(effect.countdownWidth()) + RIGHT_PADDING,
                MIN_WARNING_WIDTH, MAX_WARNING_WIDTH));
        }
        List<Item> items = new ArrayList<>();

        for (Motion motion : motions.values()) {
            Effect effect = motion.effect;
            float progress = smooth(motion.phase);
            if (motion.captureDeparture) {
                motion.departureX = motion.positioned ? motion.x : compactX + stripOffset;
            }
            // Hold the departure slot while the strip closes. Multiplying an
            // already-closing slot by progress makes simultaneous icons collide.
            float departureX = motion.warning ? motion.departureX : compactX + stripOffset;
            float targetX = departureX * (1.0F - progress);
            float verticalProgress = 1.0F - (1.0F - progress) * (1.0F - progress);
            float targetY = baseY + (warningY - baseY) * verticalProgress;
            if (!motion.positioned) {
                motion.x = compactX + stripOffset;
                motion.y = baseY;
                motion.cardWidth = warningWidth;
                motion.positioned = true;
            }
            float follow = (float) -Math.expm1(-30.0F * dt);
            motion.x += (targetX - motion.x) * follow;
            motion.y += (targetY - motion.y) * follow;
            // Shared card edges remain aligned, including when a longer name
            // expires alongside an existing warning. The text fades in last.
            motion.cardWidth += (warningWidth - motion.cardWidth) * (float) -Math.expm1(-16.0F * dt);
            float reveal = smooth((progress - 0.3F) / 0.5F);
            float cardWidth = ICON_PADDING * 2.0F + ICON_SIZE
                + (motion.cardWidth - ICON_PADDING * 2.0F - ICON_SIZE) * reveal;
            float alpha = smooth(motion.life);
            if (progress <= 0.001F) {
                // Let the joined surface grow underneath a newly added sprite
                // before it becomes visible outside the previous tray edge.
                float rightRoom = (surfaceWidth - motion.x - ICON_PADDING - ICON_SIZE) / ICON_PADDING;
                float bottomRoom = (surfaceHeight - motion.y - (ROW_HEIGHT + ICON_SIZE) * 0.5F)
                    / ((ROW_HEIGHT - ICON_SIZE) * 0.5F);
                alpha *= smooth(Math.min(rightRoom, bottomRoom));
            }
            Item item = new Item(effect.key(), effect.ticks(), motion.x, motion.y, progress,
                cardWidth, reveal, 1.0F, alpha);
            items.add(item);
            if (alpha > 0.002F) {
                width = Math.max(width, motion.x + (reveal > 0.001F ? cardWidth : ICON_PADDING + ICON_SIZE));
                height = Math.max(height, motion.y + ROW_HEIGHT);
            }
            compactX += (ICON_SIZE + ICON_GAP) * (1.0F - progress);
            if (progress > 0.0F) warningY += WARNING_HEIGHT + WARNING_GAP;
        }
        return new Layout(List.copyOf(items), stripWidth, stripHeight, occupancy, width, height, active.size(),
            surfaceWidth, surfaceHeight);
    }

    public static boolean warning(int ticks) {
        return ticks >= 0 && ticks < WARNING_START_TICKS;
    }

    public static String warningCountdown(int ticks) {
        long seconds = (Math.max(0L, (long) ticks) + 19L) / 20L;
        return Math.clamp(seconds, 0L, 10L) + "s";
    }

    /** Only the name and countdown breathe yellow; sprites retain their original colors. */
    public static float pulse(int ticks, long nowNanos) {
        if (!warning(ticks)) return 0.0F;
        double phase = Math.floorMod(nowNanos, 1_200_000_000L) / 1_200_000_000.0D;
        return (float) (0.45D + 0.55D * (0.5D + 0.5D * Math.sin(phase * Math.PI * 2.0D)));
    }

    private static float smooth(float value) {
        float t = Math.clamp(value, 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static float safe(float value) {
        return Float.isFinite(value) ? Math.max(0.0F, value) : 0.0F;
    }

    private static final class Motion {
        Effect effect;
        float phase;
        float life;
        float x;
        float y;
        float cardWidth;
        float departureX;
        boolean positioned;
        boolean warning;
        boolean captureDeparture;
    }
}
