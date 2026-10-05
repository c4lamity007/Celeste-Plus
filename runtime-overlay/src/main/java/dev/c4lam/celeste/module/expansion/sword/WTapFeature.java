package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.action.SwordAttackCoordinator;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;

/** Tick-bounded forward release after a real vanilla entity attack. */
public final class WTapFeature extends WTapInterceptFeature {
    private int configuredDuration = 1;
    private boolean groundOnly = true;
    private long startsAtTick = Long.MAX_VALUE;
    private long endsAtTick = Long.MIN_VALUE;
    private boolean ownsForwardKey;

    @Override
    protected SwordAttackCoordinator.AttackPolicy attackPolicy() {
        return new SwordAttackCoordinator.AttackPolicy() {
            @Override public void attacked(MinecraftClient client, net.minecraft.entity.Entity target) {
                if (target instanceof LivingEntity living && WTapSwordExpansionTargets.validLiving(client, living, 6.0D, true))
                    onAttack(client, living);
            }
        };
    }

    @Override
    public String name() {
        return "W Tap";
    }

    @Override
    protected void refresh(ModuleContext context) {
        configuredDuration = Math.max(1, (int) Math.round(context.slider("Duration ticks", 1.0D)));
        groundOnly = context.toggle("Ground only", true);
    }

    @Override
    protected void tick(ModuleContext context) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (startsAtTick != Long.MAX_VALUE && tick >= endsAtTick) {
            restore(context.client());
        }
        if (tick < startsAtTick || tick >= endsAtTick) {
            return;
        }
        MinecraftClient client = context.client();
        if (client.currentScreen != null || client.player == null
            || !WTapPhysicalBinding.isDown(client, client.options.forwardKey)) {
            restore(client);
            return;
        }
        if (client.options.forwardKey.isPressed()) {
            ownsForwardKey = true;
            client.options.forwardKey.setPressed(false);
        }
    }

    @Override
    public void onEndTick(ModuleContext context) {
        long tick = ActionScheduler.getInstance().tickNumber();
        if (startsAtTick != Long.MAX_VALUE && tick + 1L >= endsAtTick) {
            restore(context.client());
        }
    }

    void onAttack(MinecraftClient client, LivingEntity target) {
        if (!active() || client.player == null || target == null
            || client.currentScreen != null || !client.player.isSprinting()
            || (groundOnly && !client.player.isOnGround())
            || !WTapPhysicalBinding.isSupported(client.options.forwardKey)
            || !WTapPhysicalBinding.isDown(client, client.options.forwardKey)) {
            return;
        }
        long firstInputTick = ActionScheduler.getInstance().tickNumber() + 1L;
        startsAtTick = Math.min(startsAtTick, firstInputTick);
        endsAtTick = Math.max(endsAtTick, firstInputTick + configuredDuration);
    }

    @Override
    protected void disabled(ModuleContext context) {
        restore(context.client());
    }

    private void restore(MinecraftClient client) {
        startsAtTick = Long.MAX_VALUE;
        endsAtTick = Long.MIN_VALUE;
        if (ownsForwardKey && client != null && client.options != null) {
            client.options.forwardKey.setPressed(WTapPhysicalBinding.isDown(client, client.options.forwardKey));
        }
        ownsForwardKey = false;
    }
}
