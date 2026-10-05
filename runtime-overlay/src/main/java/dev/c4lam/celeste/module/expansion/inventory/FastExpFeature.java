package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.item.Items;

/** Repeats one ordinary use click per configured tick interval for XP bottles. */
public final class FastExpFeature extends FastExpUseRepeater {
    // Specific XP handling wins the exclusive use-input lease when generic
    // Fast Place is configured for all items.
    private static final int PRIORITY = 101;

    @Override
    public String name() {
        return "Fast EXP";
    }

    @Override
    protected int priority() {
        return PRIORITY;
    }

    @Override
    protected boolean eligible(ModuleContext context) {
        // Minecraft's normal doItemUse checks main hand first. Requiring the
        // bottle there prevents an unrelated main-hand item from consuming the
        // repeater's one allowed use for this tick.
        return context.client().player.getMainHandStack().isOf(Items.EXPERIENCE_BOTTLE);
    }

    @Override
    protected int delayTicks(ModuleContext context) {
        return (int) Math.round(context.slider("Delay", 0.0D));
    }
}
