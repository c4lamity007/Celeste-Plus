package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;

/**
 * Replaces vanilla's held-use repeat with at most one invocation of the same
 * private {@code MinecraftClient#doItemUse} path per eligible client tick.
 */
abstract class HeldUseRepeater implements ModuleFeature {
    private static final UseInputLease USE_INPUT = UseInputLease.getInstance();

    private long lastInvocationTick = Long.MIN_VALUE / 2L;

    protected abstract int priority();

    protected abstract boolean eligible(ModuleContext context);

    protected abstract int delayTicks(ModuleContext context);

    @Override
    public final void onEnable(ModuleContext context) {
        lastInvocationTick = Long.MIN_VALUE / 2L;
    }

    @Override
    public final void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (!canOwnUse(context)) {
            USE_INPUT.release(this, client);
            RotationManager.getInstance().release(this);
            return;
        }

        long tick = ActionScheduler.getInstance().tickNumber();

        // Claim the physical hold before rotations resolve and before the
        // scheduler checks for a competing vanilla input. Draining this queue
        // prevents handleInputEvents from issuing a second click later this tick.
        if (!USE_INPUT.acquire(this, priority(), tick, client)) {
            ActionScheduler.getInstance().cancel(this);
            RotationManager.getInstance().release(this);
            return;
        }

        int delay = Math.max(0, Math.min(4, delayTicks(context)));
        int interval = Math.max(1, delay);
        if (tick - lastInvocationTick < interval) {
            return;
        }

        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(client.player.getYaw(), client.player.getPitch()))
            .priority(priority())
            .lifetimeTicks(1)
            .smoothing(180.0F, 180.0F)
            .sensitivityQuantized(false)
            .moveFix(MoveFixMode.STRICT)
            .build());

        ActionScheduler.getInstance().submit(this, priority(),
            actionClient -> invokeOneVanillaUse(context, actionClient, interval));
    }

    @Override
    public final void onDisable(ModuleContext context) {
        release(context.client());
    }

    @Override
    public final void onWorldChanged(ModuleContext context) {
        release(context.client());
        lastInvocationTick = Long.MIN_VALUE / 2L;
    }

    private void invokeOneVanillaUse(ModuleContext context, MinecraftClient client, int interval) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (!context.module().enabled()
            || tick - lastInvocationTick < interval
            || !canOwnUse(context)
            || !USE_INPUT.owns(this)
            || !RotationManager.getInstance().isOwnedBy(this)) {
            return;
        }

        // One call is one normal client click: block/entity interaction,
        // interactItem fallback, swing/equip reset, and the ordinary cooldown.
        MinecraftAccess.useItem(client);
        lastInvocationTick = tick;
    }

    private boolean canOwnUse(ModuleContext context) {
        MinecraftClient client = context.client();
        return client.player != null
            && client.world != null
            && client.interactionManager != null
            && client.currentScreen == null
            && client.isWindowFocused()
            && client.player.isAlive()
            && !client.player.hasVehicle()
            && !client.player.isSpectator()
            && !client.player.isUsingItem()
            && !client.interactionManager.isBreakingBlock()
            && UseInputLease.physicalDown(client)
            && eligible(context);
    }

    private void release(MinecraftClient client) {
        USE_INPUT.release(this, client);
        GameplayFoundation.releaseOwner(this);
    }

}
