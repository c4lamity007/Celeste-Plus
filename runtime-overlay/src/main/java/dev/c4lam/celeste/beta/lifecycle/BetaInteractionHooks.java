package dev.c4lam.celeste.beta.lifecycle;

import dev.c4lam.celeste.compat.InteractionSlotHooks;
import dev.c4lam.celeste.compat.KeepSprintHooks;
import dev.c4lam.celeste.compat.OwnFluidHooks;
import dev.celestebeta.host.InteractionHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

/** Same module-controlled behavior as startup; paired cleanup remains active until disconnection. */
public final class BetaInteractionHooks implements InteractionHookBridge.Handler, AutoCloseable {
    private final InteractionHookBridge.Registration registration;
    private BetaInteractionHooks() { registration = InteractionHookBridge.install(this); }

    public static BetaInteractionHooks connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Connect interactions on the client thread");
        InteractionHookBridge.assertVisibleFrom(client.getClass().getClassLoader());
        InteractionHookBridge.assertVisibleFrom(BetaInteractionHooks.class.getClassLoader());
        return new BetaInteractionHooks();
    }

    public int selectedSlot(Object inventory, int vanilla) { return InteractionSlotHooks.synchronizationSlot(vanilla); }
    public void beginBreaking(Object manager) { InteractionSlotHooks.beginBreaking(manager); }
    public void endBreaking(Object manager) { InteractionSlotHooks.endBreaking(manager); }
    public void beforeAttack(Object manager, Object player) { KeepSprintHooks.capture(manager, (PlayerEntity) player); }
    public void afterAttack(Object manager, Object target) { KeepSprintHooks.afterAttack(manager, (Entity) target); }
    public void beforeItem(Object manager, Object player, Object hand) { OwnFluidHooks.capture(manager, (PlayerEntity) player, (Hand) hand); }
    public void afterItem(Object manager, Object result) { OwnFluidHooks.afterInteract(manager, (ActionResult) result); }

    @Override public void close() {
        registration.close();
        InteractionSlotHooks.clear();
        KeepSprintHooks.clear();
        OwnFluidHooks.clear();
    }
}
