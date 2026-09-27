package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.expansion.LocalInputOwnership;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;

/** Performs one ordinary vanilla jump after a newly observed entity hit. */
public final class JumpResetFeature implements ModuleFeature {
    private int previousHurtTime;
    private int pendingTicks = -1;
    private boolean ownsJumpKey;

    @Override
    public String name() {
        return "Jump Reset";
    }

    @Override
    public void onEnable(ModuleContext context) {
        reset(context);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        if (context.client().player == null || context.client().world == null
            || context.client().currentScreen != null || !context.client().player.isAlive()) {
            reset(context);
            return;
        }

        int hurtTime = context.client().player.hurtTime;
        boolean newHit = hurtTime > 0 && hurtTime > previousHurtTime;
        previousHurtTime = hurtTime;
        if (newHit && entityCausedDamage(context)) {
            double chance = Math.max(0.0D, Math.min(100.0D, context.slider("Chance", 100.0D)));
            if (context.client().world.getRandom().nextDouble() * 100.0D < chance) {
                pendingTicks = Math.max(0, (int) Math.round(context.slider("Delay ticks", 0.0D)));
            }
        }

        if (pendingTicks < 0) {
            return;
        }
        if (pendingTicks > 0) {
            pendingTicks--;
            return;
        }

        pendingTicks = -1;
        if (context.client().currentScreen == null
            && context.client().player.isAlive()
            && context.client().player.isOnGround()
            && !context.client().player.isUsingItem()
            && !context.client().player.hasVehicle()
            && !context.client().player.isGliding()) {
            ownsJumpKey = LocalInputOwnership.setSynthetic(
                context.client(), context.client().options.jumpKey, true, ownsJumpKey);
        }
    }

    @Override
    public void onEndTick(ModuleContext context) {
        releaseJump(context);
    }

    @Override
    public void onDisable(ModuleContext context) {
        reset(context);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        reset(context);
    }

    private boolean entityCausedDamage(ModuleContext context) {
        DamageSource source = context.client().player.getRecentDamageSource();
        if (source == null) {
            return false;
        }
        Entity attacker = source.getAttacker();
        if (attacker == null || attacker == context.client().player || !attacker.isAlive()) {
            return false;
        }
        return !(attacker instanceof PlayerEntity player) || !FriendManager.getInstance().isFriend(player);
    }

    private void reset(ModuleContext context) {
        releaseJump(context);
        previousHurtTime = context.client().player == null ? 0 : context.client().player.hurtTime;
        pendingTicks = -1;
    }

    private void releaseJump(ModuleContext context) {
        LocalInputOwnership.release(context.client(), context.client().options.jumpKey, ownsJumpKey);
        ownsJumpKey = false;
    }
}
