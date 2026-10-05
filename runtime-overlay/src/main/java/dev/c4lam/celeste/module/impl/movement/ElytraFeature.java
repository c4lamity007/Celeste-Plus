package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;

import java.time.Duration;

/** Silent pitch assistance while vanilla reports an active elytra glide. */
public final class ElytraFeature implements ModuleFeature {
    private static final int ROTATION_PRIORITY = 5;

    @Override
    public String name() {
        return "Elytra";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (context.client().player == null || context.client().world == null
            || context.client().currentScreen != null
            || !context.client().player.isAlive() || context.client().player.isSpectator()
            || context.client().player.hasVehicle() || !context.client().player.isGliding()) {
            releaseRotation();
            return;
        }

        float pitch = (float) context.slider("Pitch", -2.0D);
        Rotation target = new Rotation(context.client().player.getYaw(), pitch);
        RotationManager.getInstance().submit(RotationTask.builder(this, target)
            .priority(ROTATION_PRIORITY)
            .lifetimeTicks(1)
            .lifetime(Duration.ofMillis(150L))
            .smoothing(180.0F, 4.0F)
            .sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT)
            .build());
    }

    @Override
    public void onDisable(ModuleContext context) {
        releaseRotation();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        releaseRotation();
    }

    private void releaseRotation() {
        RotationManager.getInstance().release(this);
    }
}
