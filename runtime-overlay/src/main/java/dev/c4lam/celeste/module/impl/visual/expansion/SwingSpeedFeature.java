package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

/** Client-side local-player swing duration. It never changes attack cooldown or packets. */
public final class SwingSpeedFeature implements ModuleFeature {
    private static volatile boolean enabled;
    private static volatile int duration = 6;
    private static volatile String mode = "Custom";
    private static volatile boolean easeChanges = true;
    private static final SwingSpeedDuration swingDuration = new SwingSpeedDuration();
    private static LivingEntity trackedPlayer;

    @Override
    public String name() {
        return "Swing Speed";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        resetSwing();
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        resetSwing();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        resetSwing();
    }

    public static int durationFor(LivingEntity entity, int vanillaDuration) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity != client.player) return vanillaDuration;
        if (!enabled) {
            // A disabled presentation module must return the vanilla value even
            // during a swing that started while a custom duration was active.
            swingDuration.reset();
            trackedPlayer = null;
            return vanillaDuration;
        }
        int requested = switch (mode) {
            case "Vanilla" -> vanillaDuration;
            case "Cinematic" -> MathHelper.clamp(Math.round(vanillaDuration * 1.5F), 1, 30);
            case "Snappy" -> MathHelper.clamp(Math.round(vanillaDuration * 0.65F), 1, 20);
            default -> duration;
        };
        if (trackedPlayer != entity) {
            trackedPlayer = entity;
            swingDuration.reset();
        }
        return swingDuration.resolve(true, easeChanges, requested, vanillaDuration,
            entity.handSwinging, entity.handSwingTicks);
    }

    private static void resetSwing() {
        trackedPlayer = null;
        swingDuration.reset();
    }

    private static void update(ModuleContext context) {
        double configured = context.slider("Speed", 6.0D);
        duration = MathHelper.clamp((int) Math.round(configured), 1, 20);
        mode = context.mode("Mode", "Custom");
        easeChanges = context.toggle("Ease changes", true);
    }
}
