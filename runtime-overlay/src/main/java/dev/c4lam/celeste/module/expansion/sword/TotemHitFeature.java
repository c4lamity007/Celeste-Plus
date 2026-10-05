package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.SwordAttackCoordinator;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import dev.c4lam.celeste.module.impl.combat.TotemHitVanillaCombatReach;

/** Uses a hotbar sword for one physical entity attack while a totem is in the main hand. */
public final class TotemHitFeature extends TotemHitInterceptFeature {
    static final int PRIORITY = 210;
    private double range = 3.25D;
    private boolean silentSwap = true;

    @Override
    protected SwordAttackCoordinator.AttackPolicy attackPolicy() {
        return new SwordAttackCoordinator.AttackPolicy() {
            @Override public SwordAttackCoordinator.SwapIntent select(MinecraftClient client) {
                return TotemHitFeature.this.select(client);
            }
        };
    }

    @Override
    public String name() {
        return "Totem Hit";
    }

    @Override
    protected void refresh(ModuleContext context) {
        range = Math.max(2.0D, context.slider("Range", 3.25D));
        silentSwap = context.toggle("Silent swap", true);
    }

    SwordAttackCoordinator.SwapIntent select(MinecraftClient client) {
        if (!active() || client.player == null || client.currentScreen != null
            || client.player.hasVehicle()
            || client.player.isUsingItem() || client.interactionManager == null
            || client.interactionManager.isBreakingBlock()
            || !client.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return null;
        }
        LivingEntity target = TotemHitSwordExpansionTargets.crosshairLiving(client, range);
        int slot = target == null ? -1 : TotemHitEnchantedHotbar.bestSword(client);
        return slot < 0 || !TotemHitVanillaCombatReach.canAttack(client, target, slot, range, true)
            || client.player.isBelowMinimumAttackCharge(
            client.player.getInventory().getStack(slot), 0
        ) ? null : new SwordAttackCoordinator.SwapIntent(this, slot, PRIORITY, silentSwap);
    }
}
