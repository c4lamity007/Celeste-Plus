package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.gui.HudTransparentScreen;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;

/** Passive snapshot source for the live mace-smash HUD guide. */
public final class SmashGuideFeature implements ModuleFeature {
    private static final double BASE_MACE_DAMAGE = 6.0D;

    private Estimate estimate;

    @Override
    public String name() {
        return "Smash Guide";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (context.client().player == null
            || (context.client().currentScreen != null
                && !(context.client().currentScreen instanceof HudTransparentScreen))
            || context.client().player.isGliding()
            || !MaceItem.shouldDealAdditionalDamage(context.client().player)
            || SmashGuideCombatLoadout.findItem(context.client(), Items.MACE) < 0) {
            estimate = null;
            return;
        }

        double fallDistance = Math.max(0.0D, context.client().player.fallDistance);
        estimate = new Estimate(BASE_MACE_DAMAGE + bonusDamage(fallDistance), fallDistance,
            context.toggle("Show height", true));
    }

    @Override
    public void onDisable(ModuleContext context) {
        estimate = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        estimate = null;
    }

    Estimate estimate() {
        return estimate;
    }

    /** Mirrors MaceItem#getBonusAttackDamage's unenchanted 1.21.11 curve. */
    private static double bonusDamage(double fallDistance) {
        if (fallDistance <= 3.0D) {
            return 4.0D * fallDistance;
        }
        if (fallDistance <= 8.0D) {
            return 12.0D + 2.0D * (fallDistance - 3.0D);
        }
        return 22.0D + (fallDistance - 8.0D);
    }

    record Estimate(double damage, double fallDistance, boolean showHeight) {
    }
}
