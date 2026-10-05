package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import dev.c4lam.celeste.action.ActionScheduler;
import net.minecraft.util.hit.EntityHitResult;

public final class ShieldBreakFeature extends ShieldBreakCombatFeature {
    private static final int PRIORITY = 70;
    private PlayerEntity target;
    private final ShieldBreakShieldBreakSupport shield = new ShieldBreakShieldBreakSupport();

    @Override
    public String name() {
        return "Shield Break";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        target = null;
        if (context.client().crosshairTarget instanceof EntityHitResult hit) {
            Entity entity = hit.getEntity();
            if (entity instanceof PlayerEntity player 
                && ShieldBreakShieldBreakSupport.blockingFrom(context.client(), player)
                && validPlayer(context.client(), player, ShieldBreakVanillaCombatReach.heldHitRange(context.client()))) {
                target = player;
            }
        }
        if (target == null
            || !shield.ready(context, target)) {
            if (target == null) shield.reset();
            target = null;
            releaseIntent();
            return;
        }
        requestRotation(target, PRIORITY);
    }

    @Override
    public void onActionTick(ModuleContext context) {
        PlayerEntity selected = target;
        if (selected == null || !ShieldBreakShieldBreakSupport.blockingFrom(context.client(), selected)) {
            return;
        }
        ActionScheduler.getInstance().submit(this, PRIORITY, client -> {
            if (target == selected && client.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() == selected && rotationReady(selected, 7.5D)) {
                shield.attempt(this, context, selected, PRIORITY, context.toggle("Silent swap", true));
            }
        });
    }

    @Override
    protected void clearTarget() {
        target = null;
        shield.reset();
    }
}
