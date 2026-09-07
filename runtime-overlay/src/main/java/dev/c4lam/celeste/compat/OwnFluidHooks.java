package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.module.expansion.inventory.OwnFluidTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/** Preserves filled-bucket capture and successful-return tracking with externally owned state. */
public final class OwnFluidHooks {
    private static final WeakIdentityStates<State> STATES = new WeakIdentityStates<>(State::new);

    private OwnFluidHooks() { }

    public static synchronized void capture(Object manager, PlayerEntity player, Hand hand) {
        State state = STATES.get(manager);
        state.pending = null;
        ItemStack stack = player.getStackInHand(hand);
        if (!stack.isOf(Items.WATER_BUCKET) && !stack.isOf(Items.LAVA_BUCKET)) return;
        state.pending = OwnFluidTracker.predictFilledBucketPlace(MinecraftClient.getInstance());
    }

    /** Called on normal return only; consume the prediction before recording a successful use. */
    public static synchronized void afterInteract(Object manager, ActionResult result) {
        State state = STATES.get(manager);
        BlockPos pending = state.pending;
        state.pending = null;
        if (pending != null && result instanceof ActionResult.Success) OwnFluidTracker.getInstance().mark(pending);
    }

    /** Release pending predictions when this Celeste runtime retires; does not change tracked fluids. */
    public static synchronized void clear() { STATES.clear(); }

    private static final class State {
        private BlockPos pending;
    }
}
