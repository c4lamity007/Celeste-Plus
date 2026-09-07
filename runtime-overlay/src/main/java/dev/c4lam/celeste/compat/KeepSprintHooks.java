package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.impl.expansion.KeepSprintFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/** Shares the original attack HEAD/RETURN state without adding a field to the interaction manager. */
public final class KeepSprintHooks {
    private static final WeakIdentityStates<State> STATES = new WeakIdentityStates<>(State::new);

    private KeepSprintHooks() { }

    public static synchronized void capture(Object manager, PlayerEntity player) {
        STATES.get(manager).wasSprinting = player != null && player.isSprinting();
    }

    /** Called on normal return only, matching the startup mixin's injection point. */
    public static synchronized void afterAttack(Object manager, Entity target) {
        State state = STATES.get(manager);
        KeepSprintFeature.afterAttack(MinecraftClient.getInstance(), target, state.wasSprinting);
        state.wasSprinting = false;
    }

    /** Release captured state when this Celeste runtime retires. */
    public static synchronized void clear() { STATES.clear(); }

    private static final class State {
        private boolean wasSprinting;
    }
}
