package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.SwordAttackCoordinator;

import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/** Cancels only the local vanilla attack invocation that cannot hit a valid target. */
public final class AntiMissFeature extends AntiMissInterceptFeature {
    private boolean protectBlocks = true;

    @Override
    protected SwordAttackCoordinator.AttackPolicy attackPolicy() {
        return new SwordAttackCoordinator.AttackPolicy() {
            @Override public boolean cancel(MinecraftClient client, boolean validatedReplacement) {
                return shouldCancel(client, validatedReplacement);
            }
        };
    }

    @Override
    public String name() {
        return "Anti Miss";
    }

    @Override
    protected void refresh(ModuleContext context) {
        protectBlocks = context.toggle("Protect blocks", true);
    }

    boolean shouldCancel(MinecraftClient client, boolean hasValidatedReplacement) {
        if (!active() || hasValidatedReplacement || client.player == null || client.currentScreen != null) {
            return false;
        }
        HitResult hit = client.crosshairTarget;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            return !protectBlocks;
        }
        if (!(hit instanceof EntityHitResult entityHit)
            || !(entityHit.getEntity() instanceof LivingEntity living)) {
            return true;
        }
        return !AntiMissSwordExpansionTargets.validLiving(client, living, 6.0D, true);
    }
}
