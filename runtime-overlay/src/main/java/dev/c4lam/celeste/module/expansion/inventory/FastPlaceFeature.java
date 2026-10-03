package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/** Repeats one ordinary use click per configured tick interval for held blocks. */
public final class FastPlaceFeature extends FastPlaceUseRepeater {
    private static final int PRIORITY = 100;

    @Override
    public String name() {
        return "Fast Place";
    }

    @Override
    protected int priority() {
        return PRIORITY;
    }

    @Override
    protected boolean eligible(ModuleContext context) {
        ItemStack main = context.client().player.getMainHandStack();
        ItemStack off = context.client().player.getOffHandStack();
        // Specialized held-use modules own these items. The generic repeater
        // must never turn their one-use-per-press contract into a held stream.
        if (specialized(main) || specialized(off)) {
            return false;
        }
        if (!context.toggle("Blocks only", true)) {
            return !main.isEmpty() || !off.isEmpty();
        }
        return main.getItem() instanceof BlockItem
            || main.isEmpty() && off.getItem() instanceof BlockItem;
    }

    private static boolean specialized(ItemStack stack) {
        return stack.isOf(Items.EXPERIENCE_BOTTLE)
            || stack.isOf(Items.WIND_CHARGE)
            || stack.isOf(Items.FIREWORK_ROCKET);
    }

    @Override
    protected int delayTicks(ModuleContext context) {
        return (int) Math.round(context.slider("Delay", 1.0D));
    }
}
