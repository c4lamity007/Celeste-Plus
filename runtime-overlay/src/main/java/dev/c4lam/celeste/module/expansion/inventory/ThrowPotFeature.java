package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;

/** Volt ThrowPot's press-to-throw behavior without camera or selected-slot mutation. */
final class ThrowPotFeature implements ModuleFeature {
    private static final int PRIORITY = 185;
    private boolean actionDown;
    private int throwsRemaining;
    private int expiresAtAge;
    private int lastActionAge = Integer.MIN_VALUE / 2;
    private ClientWorld intentWorld;
    private int intentSlot = -1;

    @Override
    public String name() {
        return "Throw Pot";
    }

    @Override
    public void onEnable(ModuleContext context) {
        actionDown = InventorySupport.actionKeyDown(context);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        clearIntent();
        MinecraftClient client = context.client();
        boolean down = InventorySupport.actionKeyDown(context);
        boolean rising = down && !actionDown;
        actionDown = down;
        if (!InventorySupport.playable(client) || !client.isWindowFocused() || client.getOverlay() != null) {
            cancel();
            return;
        }
        if (rising && client.player.getHealth() + client.player.getAbsorptionAmount()
            <= context.slider("Health", 14.0D)) {
            throwsRemaining = Math.max(1, Math.min(3, (int) Math.round(context.slider("Count", 1.0D))));
            expiresAtAge = client.player.age + 30;
        }
        if (throwsRemaining <= 0 || client.player.age > expiresAtAge || !delayElapsed(context)) {
            if (throwsRemaining <= 0 || client.player.age > expiresAtAge) {
                GameplayFoundation.releaseOwner(this);
            }
            return;
        }
        int slot = InventorySupport.hotbarSlot(client, InventorySupport::isSplashHealing);
        if (slot < 0) {
            cancel();
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
        if (world == null || slot < 0 || !InventorySupport.downReady(this)) return;
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!InventorySupport.playable(client) || !client.isWindowFocused() || client.getOverlay() != null
                || client.world != world || !context.module().enabled()
                || throwsRemaining <= 0 || client.player.age > expiresAtAge || !delayElapsed(context)
                || slot > 8 || !InventorySupport.isSplashHealing(client.player.getInventory().getStack(slot))
                || !InventorySupport.downReady(this)) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                if (InventorySupport.isSplashHealing(client.player.getMainHandStack())
                    && InventorySupport.useItem(client, Hand.MAIN_HAND)) {
                    lastActionAge = client.player.age;
                    throwsRemaining--;
                }
            });
        });
    }

    private boolean delayElapsed(ModuleContext context) {
        if (context.client().player == null) return false;
        int delay = Math.max(1, (int) Math.round(context.slider("Delay", 4.0D)));
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
        actionDown = false;
        throwsRemaining = 0;
        expiresAtAge = 0;
        lastActionAge = Integer.MIN_VALUE / 2;
        cancel();
    }

    private void cancel() {
        throwsRemaining = 0;
        expiresAtAge = 0;
        clearIntent();
        GameplayFoundation.releaseOwner(this);
    }

    private void clearIntent() {
        intentWorld = null;
        intentSlot = -1;
    }
}
