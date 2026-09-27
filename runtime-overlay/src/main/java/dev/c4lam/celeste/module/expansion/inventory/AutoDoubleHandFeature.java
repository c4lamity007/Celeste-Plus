package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;

/** Danger-based main-hand totem policy using an ordinary visible selection. */
final class AutoDoubleHandFeature implements ModuleFeature {
    private static final int PRIORITY = 230;
    private int originalVisibleSlot = -1;
    private int switchedVisibleSlot = -1;
    private ClientPlayerEntity visiblePlayer;

    @Override
    public String name() {
        return "Auto Double Hand";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        var client = context.client();
        if (!InventorySupport.playable(client) || client.player.isUsingItem()) {
            release(client);
            return;
        }
        double health = client.player.getHealth() + client.player.getAbsorptionAmount();
        boolean dangerousHealth = health <= context.slider("Health", 8.0D);
        boolean dangerousFall = context.toggle("Falling", true) && !client.player.isOnGround()
            && client.player.fallDistance >= context.slider("Fall distance", 5.0D);
        if (!dangerousHealth && !dangerousFall) {
            release(client);
            return;
        }
        int slot = InventorySupport.hotbarSlot(client, Items.TOTEM_OF_UNDYING);
        if (slot < 0) {
            release(client);
            return;
        }
        SilentSlotManager slots = SilentSlotManager.getInstance();
        slots.release(this);
        int visible = client.player.getInventory().getSelectedSlot();
        if (visible != slot) {
            if (originalVisibleSlot < 0 || client.player != visiblePlayer || visible != switchedVisibleSlot) {
                // A manual scroll/number-key selection during danger becomes the new restore target.
                originalVisibleSlot = visible;
                visiblePlayer = client.player;
            }
            if (slots.selectVisibleSlot(client, slot)) {
                switchedVisibleSlot = slot;
            }
        }
    }

    @Override
    public void onDisable(ModuleContext context) { release(context.client()); }

    @Override
    public void onWorldChanged(ModuleContext context) { release(context.client()); }

    private void release(net.minecraft.client.MinecraftClient client) {
        GameplayFoundation.releaseOwner(this);
        restoreVisible(client);
    }

    private void restoreVisible(net.minecraft.client.MinecraftClient client) {
        if (client.player == visiblePlayer && originalVisibleSlot >= 0 && originalVisibleSlot <= 8
            && client.player.getInventory().getSelectedSlot() == switchedVisibleSlot) {
            SilentSlotManager.getInstance().selectVisibleSlot(client, originalVisibleSlot);
        }
        originalVisibleSlot = -1;
        switchedVisibleSlot = -1;
        visiblePlayer = null;
    }
}
