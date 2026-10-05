package dev.c4lam.celeste.rotation;

import dev.c4lam.celeste.module.delivery.ModuleHooks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Gliding adapter around the shared arbiter, not a competing rotation owner. */
public final class ElytraSilentAim {
    private ElytraSilentAim() { }

    /** One throw tick, with at most one extra tick for the existing bounded aim step. */
    public static boolean withinAimWindow(long startedTick, long currentTick) {
        return currentTick == startedTick || (startedTick != Long.MAX_VALUE && currentTick == startedTick + 1);
    }

    /** Boosting has a separate vanilla direction read. Conservatively defer while an owned rocket lives. */
    public static boolean canAim(MinecraftClient client) {
        if (client.player == null || client.world == null || !client.player.isAlive()
            || !client.player.isGliding() || client.player.hasVehicle()) return false;
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof FireworkRocketEntity rocket && rocket.isAlive() && rocket.getOwner() == client.player) {
                return false;
            }
        }
        return true;
    }

    /** Uses the solved look for packets and real flight, without rotating the camera or ground inputs. */
    public static boolean submit(Object owner, Rotation target, int priority) {
        if (!canAim(MinecraftClient.getInstance())) {
            RotationManager.getInstance().release(owner);
            return false;
        }
        return RotationManager.getInstance().submit(RotationTask.builder(owner, target)
            .priority(priority).lifetimeTicks(1).smoothing(180, 90)
            .sensitivityQuantized(false).moveFix(MoveFixMode.OFF).glidingPhysics(true).build());
    }

    /** Existing calcGlidingVelocity hook supplies this look to vanilla; never writes velocity itself. */
    public static Vec3d physicsVector(LivingEntity entity) {
        if (!alignsPhysics(entity)) return entity.getRotationVector();
        RotationManager rotations = RotationManager.getInstance();
        float yaw = rotations.outgoingYawOr(entity.getYaw());
        float pitch = rotations.serverPitchOr(entity.getPitch());
        // Use both the actual outgoing yaw phase and vanilla's float/table vector.
        // A double-trig projectile ray is not interchangeable at vertical pitch.
        return entity.getRotationVector(pitch, yaw);
    }

    public static float physicsPitch(LivingEntity entity) {
        return alignsPhysics(entity) ? RotationManager.getInstance().serverPitchOr(entity.getPitch()) : entity.getPitch();
    }

    private static boolean alignsPhysics(LivingEntity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == entity && client.world != null && entity.isAlive()
            && entity.isGliding() && !entity.hasVehicle() && RotationManager.getInstance().glidingPhysics();
    }

    /**
     * Runs at LivingEntityRenderer.render HEAD, after PlayerEntityRenderer finishes updateGliding.
     * Setting banking at the parent updateRenderState tail would be overwritten by camera-based
     * banking in the subclass. All projected flight angles must use the same interpolated look.
     */
    public static void finishRender(LivingEntityRenderState state) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (!(state instanceof PlayerEntityRenderState pose) || player == null
            || pose.id != player.getId() || !pose.isGliding || !player.isGliding()
            || !((boolean) ModuleHooks.invoke("sword_aura", "rotation.visible", false, client))) return;
        float tickDelta = MathHelper.clamp(pose.age - player.age, 0, 1);
        Rotation look = RotationManager.getInstance().renderRotation(tickDelta);
        Vec3d movement = player.getState().getVelocity().lerp(player.getVelocity(), tickDelta);
        Vec3d direction = player.getRotationVector(look.pitch(), look.yaw());
        ElytraRotationMath.Bank bank = ElytraRotationMath.bank(movement.x, movement.z, direction.x, direction.z);
        pose.bodyYaw = look.yaw();
        pose.relativeHeadYaw = 0;
        pose.pitch = look.pitch();
        pose.applyFlyingRotation = bank.apply();
        pose.flyingRotation = bank.radians();
        // Leave gliding progress, the vanilla -45-degree gliding head, wings and actual movement alone.
    }
}
