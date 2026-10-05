package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.function.Predicate;

/** Private-client/Cyemer one-shot elytra/chestplate right-click swap, safely restored. */
public final class ElytraSwapFeature implements ModuleFeature {
    private static final int PRIORITY = 150;
    private boolean queued;

    @Override
    public String name() {
        return "Elytra Swap";
    }

    @Override
    public void onEnable(ModuleContext context) {
        queued = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (!queued || !ElytraSwapInventorySupport.playable(context.client())) return;
        boolean wearingElytra = context.client().player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        Predicate<ItemStack> wanted = wearingElytra ? ElytraSwapFeature::isChestplate : stack -> stack.isOf(Items.ELYTRA);
        int slot = ElytraSwapInventorySupport.hotbarSlot(context.client(), wanted);
        if (slot < 0) {
            context.client().player.sendMessage(Text.literal(wearingElytra
                ? "Celeste: no chestplate in the hotbar" : "Celeste: no elytra in the hotbar"), true);
            queued = false;
            context.module().setEnabled(false);
            return;
        }
        var world = context.client().world;
        boolean submitted = ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (!queued || !ElytraSwapInventorySupport.playable(client) || client.world != world || slot > 8
                || !wanted.test(client.player.getInventory().getStack(slot))
                || client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA) != wearingElytra) return;
            SilentSlotManager.getInstance().runWithSlot(this, slot, PRIORITY,
                context.toggle("Silent swap", true), () -> {
                ElytraSwapInventorySupport.useItem(client, Hand.MAIN_HAND);
                queued = false;
                context.module().setEnabled(false);
            });
        });
        if (!submitted) return;
    }

    private static boolean isChestplate(ItemStack stack) {
        return stack.isOf(Items.LEATHER_CHESTPLATE) || stack.isOf(Items.CHAINMAIL_CHESTPLATE)
            || stack.isOf(Items.IRON_CHESTPLATE) || stack.isOf(Items.GOLDEN_CHESTPLATE)
            || stack.isOf(Items.DIAMOND_CHESTPLATE) || stack.isOf(Items.NETHERITE_CHESTPLATE);
    }

    @Override
    public void onDisable(ModuleContext context) { queued = false; }

    @Override
    public void onWorldChanged(ModuleContext context) { queued = false; }
}
