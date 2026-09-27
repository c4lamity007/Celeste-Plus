package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;

/** Cyemer AutoPot concept rebuilt as a bounded silent-rotation state machine. */
final class AutoPotFeature implements ModuleFeature {
    private static final int PRIORITY = 180;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private ClientWorld intentWorld;
    private int intentSlot = -1;

    @Override
    public String name() {
        return "Auto Pot";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        clearIntent();
        MinecraftClient client = context.client();
        if (!InventorySupport.playable(client) || client.player.getHealth() + client.player.getAbsorptionAmount()
            > context.slider("Health", 10.0D) || !delayElapsed(context)) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        int slot = InventorySupport.hotbarSlot(client, InventorySupport::isSplashHealing);
        if (slot < 0) {
            GameplayFoundation.releaseOwner(this);
            return;
        }
        intentWorld = client.world;
        intentSlot = slot;
        InventorySupport.requestDown(this, client.player, PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        ClientWorld world = intentWorld;
        int slot = intentSlot;
        if (world == null || slot < 0 || !InventorySupport.downReady(this)) {
            return;
        }
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> execute(client, context, world, slot));
    }

    private void execute(MinecraftClient client, ModuleContext context, ClientWorld world, int slot) {
        if (!InventorySupport.playable(client) || client.world != world || !context.module().enabled()
            || client.player.getHealth() + client.player.getAbsorptionAmount() > context.slider("Health", 10.0D)
            || !delayElapsed(new ModuleContext(client, context.module())) || !InventorySupport.downReady(this)
            || slot > 8 || !InventorySupport.isSplashHealing(client.player.getInventory().getStack(slot))) {
            return;
        }
        SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
            context.toggle("Silent swap", true), () -> {
            if (InventorySupport.isSplashHealing(client.player.getMainHandStack())
                && InventorySupport.useItem(client, Hand.MAIN_HAND)) {
                lastActionAge = client.player.age;
            }
        });
    }

    private boolean delayElapsed(ModuleContext context) {
        if (context.client().player == null) return false;
        if (context.client().player.age < lastActionAge) lastActionAge = Integer.MIN_VALUE / 2;
        int delay = Math.max(1, (int) Math.round(context.slider("Delay", 8.0D)));
        return context.client().player.age - lastActionAge >= delay;
    }

    @Override
    public void onDisable(ModuleContext context) {
        reset();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        reset();
    }

    private void reset() {
        clearIntent();
        lastActionAge = Integer.MIN_VALUE / 2;
        GameplayFoundation.releaseOwner(this);
    }

    private void clearIntent() {
        intentWorld = null;
        intentSlot = -1;
    }
}
