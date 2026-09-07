package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.compat.KeyboardInputAccess;
import dev.c4lam.celeste.compat.RotationHooks;
import dev.celestebeta.host.RotationHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;

/** One client-thread registration, sharing the startup mixins' exact gameplay helpers. */
public final class BetaRotationHooks implements RotationHookBridge.Handler, AutoCloseable {
    private final RotationHookBridge.Registration registration;
    private BetaRotationHooks() { registration = RotationHookBridge.install(this); }

    public static BetaRotationHooks connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Connect rotations on the client thread");
        RotationHookBridge.assertVisibleFrom(client.getClass().getClassLoader());
        RotationHookBridge.assertVisibleFrom(BetaRotationHooks.class.getClassLoader());
        RotationHookBridge.verifyVanillaAccess(Entity.class);
        KeyboardInputAccess.verifyLateMember();
        return new BetaRotationHooks();
    }

    public float outgoingYaw(Object entity) { return RotationHooks.outgoingYaw((ClientPlayerEntity) entity); }
    public float outgoingPitch(Object entity) { return RotationHooks.outgoingPitch((ClientPlayerEntity) entity); }
    public float itemYaw(Object entity) { return RotationHooks.itemYaw((PlayerEntity) entity); }
    public float itemPitch(Object entity) { return RotationHooks.itemPitch((PlayerEntity) entity); }
    public float movementYaw(Object entity) { return RotationHooks.movementYaw((Entity) entity); }
    public Object glidingVector(Object entity) { return RotationHooks.glidingVector((LivingEntity) entity); }
    public float glidingPitch(Object entity) { return RotationHooks.glidingPitch((LivingEntity) entity); }
    public float sprintJumpYaw(Object entity) { return RotationHooks.sprintJumpYaw((LivingEntity) entity); }
    public void keyboardTick(Object value) {
        Input input = (Input) value;
        var correction = RotationHooks.keyboardCorrection(input, input.getMovementInput(), input.playerInput);
        if (correction != null) {
            KeyboardInputAccess.setMovement(input, correction.movement());
            input.playerInput = correction.keys();
        }
    }
    public Object[] selectedStack(Object inventory, Object replacement, boolean set) {
        return RotationHooks.selectedStack((PlayerInventory) inventory, replacement, set);
    }
    @Override public void close() { registration.close(); }
}
