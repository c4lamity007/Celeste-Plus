package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.action.LocalInputOwnership;
import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

/** One ordinary firework interaction per physical use-key edge while gliding. */
public final class AutoFireworkFeature implements ModuleFeature {
    private static final int PRIORITY = 105;
    private boolean useDown;
    private boolean suppressingPress;
    private int lastUseAge = Integer.MIN_VALUE / 2;

    @Override
    public String name() {
        return "Auto Firework";
    }

    @Override
    public void onEnable(ModuleContext context) {
        useDown = LocalInputOwnership.physicalDown(context.client(), context.client().options.useKey);
        suppressingPress = false;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        boolean down = LocalInputOwnership.physicalDown(client, client.options.useKey);
        boolean rising = down && !useDown;
        useDown = down;
        if (!down) {
            suppressingPress = false;
            return;
        }
        if (suppressingPress) {
            client.options.useKey.setPressed(false);
        }
        if (!rising || client.player == null || client.interactionManager == null
            || client.currentScreen != null || !client.player.isGliding()
            || !AutoFireworkTiming.ready(client.player.age, lastUseAge)) {
            return;
        }
        int slot = -1;
        Hand hand = null;
        if (client.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)) {
            hand = Hand.MAIN_HAND;
        } else if (client.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            hand = Hand.OFF_HAND;
        } else {
            slot = findRocket(client);
            if (slot >= 0) hand = Hand.MAIN_HAND;
        }
        if (hand == null) return;

        RotationManager.getInstance().submit(RotationTask.builder(
                this, new Rotation(client.player.getYaw(), client.player.getPitch()))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180, 180)
            .sensitivityQuantized(false).moveFix(MoveFixMode.STRICT).build());

        suppressingPress = true;
        client.options.useKey.setPressed(false);
        while (client.options.useKey.wasPressed()) {
        }
        submit(context, slot, hand);
    }

    private void submit(ModuleContext context, int slot, Hand hand) {
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (client.player == null || client.interactionManager == null || client.currentScreen != null
                || !context.module().enabled() || !client.player.isGliding()
                || !RotationManager.getInstance().isOwnedBy(this)) {
                return;
            }
            Runnable use = () -> {
                ItemStack stack = client.player.getStackInHand(hand);
                if (!stack.isOf(Items.FIREWORK_ROCKET)) {
                    return;
                }
                ActionResult result = client.interactionManager.interactItem(client.player, hand);
                if (result instanceof ActionResult.Success success) {
                    if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                        client.player.swingHand(hand);
                    }
                    client.gameRenderer.firstPersonRenderer.resetEquipProgress(hand);
                    lastUseAge = client.player.age;
                }
            };
            if (slot < 0) {
                use.run();
            } else if (slot <= 8 && client.player.getInventory().getStack(slot).isOf(Items.FIREWORK_ROCKET)) {
                SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                    context.toggle("Silent swap", true), use);
            }
        });
    }

    private static int findRocket(MinecraftClient client) {
        if (client.player == null) return -1;
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(Items.FIREWORK_ROCKET)) return slot;
        }
        return -1;
    }

    @Override
    public void onDisable(ModuleContext context) {
        restoreUse(context.client());
        RotationManager.getInstance().release(this);
        useDown = false;
        suppressingPress = false;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        restoreUse(context.client());
        RotationManager.getInstance().release(this);
        useDown = false;
        suppressingPress = false;
        lastUseAge = Integer.MIN_VALUE / 2;
    }

    private void restoreUse(MinecraftClient client) {
        if (suppressingPress) {
            client.options.useKey.setPressed(LocalInputOwnership.physicalDown(client, client.options.useKey));
        }
    }
}
