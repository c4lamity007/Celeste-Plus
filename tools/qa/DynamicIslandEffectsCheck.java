package dev.c4lam.celeste.hud;

import java.util.List;

/** Offline behavior checks; rendering still needs the actual Minecraft build. */
public final class DynamicIslandEffectsCheck {
    private static int checks;
    private static DynamicIslandEffectExpiry.Effect effect(String name, int ticks) {
        return new DynamicIslandEffectExpiry.Effect(name, "minecraft:mob_effect/" + name.toLowerCase(), ticks);
    }

    public static void main(String[] args) {
        var expiry = new DynamicIslandEffectExpiry();
        check(expiry.update(10, false, List.of(effect("Speed", 2))).isEmpty(), "first observation is silent");
        check(expiry.update(11, false, List.of(effect("Speed", 1))).isEmpty(), "one tick remaining is not expiry");
        check(expiry.update(12, false, List.of()).equals(List.of(effect("Speed", 1))), "actual natural removal emits once");
        check(expiry.update(13, false, List.of()).isEmpty(), "removal is not repeated");
        expiry.update(14, false, List.of(effect("Speed", 100)));
        check(expiry.update(15, false, List.of()).isEmpty(), "early removal is silent");
        expiry.update(16, false, List.of(effect("Strength", -1)));
        check(expiry.update(17, false, List.of()).isEmpty(), "infinite-effect removal is silent");
        expiry.update(18, false, List.of(effect("Speed", 1)));
        check(expiry.update(19, false, List.of(effect("Speed", 600))).isEmpty(), "refresh is not expiry");
        expiry.update(20, true, List.of(effect("Speed", 1)));
        check(expiry.update(21, false, List.of()).isEmpty(), "milk suppresses removal even on final effect tick");
        expiry.update(30, false, List.of(effect("Speed", 1)));
        expiry.reset();
        check(expiry.update(31, false, List.of()).isEmpty(), "death/world/disable reset discards old effects");
        expiry.update(32, false, List.of(effect("Speed", 1)));
        check(expiry.update(50, false, List.of()).isEmpty(), "hidden HUD interval does not replay stale expiry");
        expiry.update(51, false, List.of(effect("Speed", 2), effect("Strength", 2)));
        check(expiry.update(53, false, List.of()).size() == 2, "small frame gap retains simultaneous expirations");
        expiry.update(54, false, List.of(effect("Speed", 1)));
        check(expiry.update(54, false, List.of()).isEmpty(), "same tick is not advanced by extra render frames");
        check(expiry.update(55, false, List.of()).size() == 1, "next tick still emits that expiration");

        DynamicIslandEvents.reset();
        DynamicIslandEvents.publishEffectExpired("Speed", "minecraft:mob_effect/speed");
        check(DynamicIslandEvents.latestAfter(0) == null, "disabled island does not collect notices");
        DynamicIslandEvents.setAccepting(true);
        DynamicIslandEvents.publishModuleToggle("First", true);
        DynamicIslandEvents.publishModuleToggle("Second", false);
        var toggle = DynamicIslandEvents.latestAfter(0);
        check(toggle.moduleName().equals("Second") && !toggle.enabled(), "module burst stays latest-only");
        DynamicIslandEvents.publishEffectExpired("Speed", "minecraft:mob_effect/speed");
        DynamicIslandEvents.publishEffectExpired("Strength", "minecraft:mob_effect/strength");
        var speed = DynamicIslandEvents.latestAfter(toggle.sequence());
        var strength = DynamicIslandEvents.latestAfter(speed.sequence());
        check(speed.effect() && strength.effect() && speed.moduleName().equals("Speed")
            && strength.moduleName().equals("Strength"), "simultaneous expirations remain ordered with both icons");
        check(DynamicIslandEvents.latestAfter(strength.sequence()) == null, "queue delivers each notice once");
        for (int index = 0; index < 30; index++) DynamicIslandEvents.publishEffectExpired("Effect " + index, "effect_" + index);
        int queued = 0;
        while (DynamicIslandEvents.latestAfter(0) != null) queued++;
        check(queued == 8, "queue remains bounded after an unusual effect burst");
        DynamicIslandEvents.publishEffectExpired("Speed", "minecraft:mob_effect/speed");
        DynamicIslandEvents.reset();
        check(DynamicIslandEvents.latestAfter(0) == null, "reset drops pending notices");

        var timeline = new DynamicIslandTimeline();
        var idle = DynamicIslandTimeline.Media.idle(88F);
        timeline.update(idle, new DynamicIslandTimeline.Notice(speed.sequence(), "Speed", false, 96F,
            speed.effectTexture()), 0.5F, 44F, 210F);
        check(timeline.current().kind() == DynamicIslandTimeline.Kind.EFFECT && timeline.notificationFraction() == 1F,
            "effect starts with its entire two-second lifetime");
        timeline.update(idle, null, 1F, 44F, 210F);
        check(timeline.notificationActive() && Math.abs(timeline.notificationFraction() - .5F) < .001F,
            "effect stays up after one second");
        timeline.update(idle, null, .999F, 44F, 210F);
        check(timeline.notificationActive(), "effect remains visible just before two seconds");
        timeline.update(idle, null, .0011F, 44F, 210F);
        check(!timeline.notificationActive() && timeline.current().kind() == DynamicIslandTimeline.Kind.MEDIA,
            "effect returns to media after two seconds");
        timeline.update(idle, new DynamicIslandTimeline.Notice(strength.sequence(), "Strength", false, 96F,
            strength.effectTexture()), .5F, 44F, 210F);
        check(timeline.notificationFraction() == 1F && timeline.current().effectTexture().equals(strength.effectTexture()),
            "queued second effect has its own full duration and sprite");
        timeline.reset();
        check(!timeline.notificationActive(), "timeline reset stops an active notice");
        timeline.update(idle, new DynamicIslandTimeline.Notice(strength.sequence() + 1, "Module", false, 96F), .1F, 44F, 210F);
        timeline.update(idle, null, 1F, 44F, 210F);
        check(!timeline.notificationActive(), "module notification keeps its existing one-second duration");
        check(DynamicIslandTimeline.idleWidth(44F, 44F, 210F) == 88F, "idle island is twice its old width");
        check(DynamicIslandTimeline.idleWidth(44F, 44F, 70F) == 70F, "idle width still respects a narrow viewport");
        float textX = DynamicIslandTimeline.centeredTextX(20F, 88F, 30F);
        check(textX - 20F == 20F + 88F - textX - 30F, "Celeste has equal left and right space");
        System.out.println("Dynamic Island effects: " + checks + " checks passed");
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
