package dev.c4lam.celeste.action;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/** Read-only arbitration against vanilla interactions due later in the tick. */
public final class VanillaInputGuard {
    private VanillaInputGuard() {
    }

    public static boolean hasInteractionConflict(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return true;
        }
        return client.player.isUsingItem()
            || client.interactionManager.isBreakingBlock()
            || activeOrQueued(client.options.attackKey)
            || activeOrQueued(client.options.useKey)
            || activeOrQueued(client.options.pickItemKey)
            || hasHeldItemMutation(client);
    }

    /** Conflicts that cannot coexist with a persistent hidden hotbar lease. */
    public static boolean hasHeldItemConflict(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return true;
        }
        return client.player.isUsingItem()
            || activeOrQueued(client.options.useKey)
            || activeOrQueued(client.options.pickItemKey)
            || hasHeldItemMutation(client);
    }

    private static boolean hasHeldItemMutation(MinecraftClient client) {
        if (SilentSlotManager.getInstance().hasVisibleSelectionChanged(client)
            || activeOrQueued(client.options.swapHandsKey)
            || activeOrQueued(client.options.dropKey)
            || activeOrQueued(client.options.inventoryKey)) {
            return true;
        }
        for (KeyBinding hotbarKey : client.options.hotbarKeys) {
            if (activeOrQueued(hotbarKey)) {
                return true;
            }
        }
        return false;
    }

    private static boolean activeOrQueued(KeyBinding binding) {
        return binding.isPressed()
            || MinecraftAccess.queuedPresses(binding) > 0;
    }
}
