package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.action.VanillaInputGuard;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;

/** Hotbar tool selector using actual block mining speed and durability. */
public final class AutoToolFeature implements ModuleFeature {
    private static final int PRIORITY = 95;
    private int originalSlot = -1;
    private int switchedSlot = -1;
    private int restoreAtAge;
    private ClientPlayerEntity visiblePlayer;

    @Override
    public String name() {
        return "Auto Tool";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || client.currentScreen != null || VanillaInputGuard.hasHeldItemConflict(client)) {
            restore(client);
            return;
        }
        boolean silent = context.toggle("Silent swap", true);
        boolean localMining = !silent && Boolean.TRUE.equals(ModuleHooks.invoke("fake_gamble", "mining.active", false));
        if ((!client.interactionManager.isBreakingBlock() && !localMining)
            || !(client.crosshairTarget instanceof BlockHitResult hit)) {
            // A hidden tool lease must never outlive active vanilla mining;
            // otherwise a later physical attack/use can reach the server with
            // a different stack than the visible hand.
            SilentSlotManager.getInstance().release(this);
            if (originalSlot >= 0 && client.player.age >= restoreAtAge) restore(client);
            return;
        }
        BlockState state = client.world.getBlockState(hit.getBlockPos());
        if (state.getHardness(client.world, hit.getBlockPos()) < 0.0F) {
            restore(client);
            return;
        }
        int best = bestTool(client, state, (int) Math.round(context.slider("Min durability", 10)));
        int selected = client.player.getInventory().getSelectedSlot();
        if (originalSlot >= 0 && selected != switchedSlot) {
            // The user selected a different slot after our visible swap. Any
            // later tool switch must restore that new choice, not the old one.
            originalSlot = -1;
            switchedSlot = -1;
            visiblePlayer = null;
        }
        if (best < 0) {
            restore(client);
            return;
        }

        int restoreDelay = Math.max(0, (int) Math.round(context.slider("Restore delay", 1)));
        restoreAtAge = client.player.age + restoreDelay;
        SilentSlotManager slots = SilentSlotManager.getInstance();
        if (silent) {
            restoreVisible(client);
            if (best == client.player.getInventory().getSelectedSlot()) {
                slots.release(this);
            } else {
                slots.acquire(this, best, PRIORITY, Math.max(2, restoreDelay + 2));
            }
            return;
        }

        slots.release(this);
        if (best == selected) {
            return;
        }
        if (originalSlot < 0) {
            originalSlot = selected;
            visiblePlayer = client.player;
        }
        if (slots.selectVisibleSlot(client, best)) {
            switchedSlot = best;
        }
    }

    private static int bestTool(MinecraftClient client, BlockState state, int minDurability) {
        int selected = client.player.getInventory().getSelectedSlot();
        int best = selected;
        float bestScore = score(client.player.getInventory().getStack(selected), state, minDurability);
        for (int slot = 0; slot < 9; slot++) {
            float score = score(client.player.getInventory().getStack(slot), state, minDurability);
            if (score > bestScore + 0.01F) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private static float score(ItemStack stack, BlockState state, int minDurability) {
        if (stack.isEmpty()) return 1.0F;
        if (stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= minDurability) return -1.0F;
        return stack.getMiningSpeedMultiplier(state);
    }

    private void restore(MinecraftClient client) {
        SilentSlotManager.getInstance().release(this);
        restoreVisible(client);
        restoreAtAge = 0;
    }

    private void restoreVisible(MinecraftClient client) {
        if (originalSlot >= 0 && client.player == visiblePlayer && originalSlot <= 8
            && client.player.getInventory().getSelectedSlot() == switchedSlot) {
            SilentSlotManager.getInstance().selectVisibleSlot(client, originalSlot);
        }
        originalSlot = -1;
        switchedSlot = -1;
        visiblePlayer = null;
    }

    @Override public void onDisable(ModuleContext context) { restore(context.client()); }
    @Override public void onWorldChanged(ModuleContext context) { restore(context.client()); }
}
