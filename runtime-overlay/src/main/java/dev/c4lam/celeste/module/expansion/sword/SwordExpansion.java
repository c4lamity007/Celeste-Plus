package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.module.ModuleManager;

/** Explicit integration point for the eight clean-room sword expansion features. */
public final class SwordExpansion {
    private static boolean registered;

    private SwordExpansion() {
    }

    public static synchronized void register(ModuleManager modules) {
        if (registered) {
            return;
        }
        registered = true;

        AimAssistFeature aimAssist = new AimAssistFeature();
        WTapFeature wTap = new WTapFeature();
        STapFeature sTap = new STapFeature();
        JumpResetFeature jumpReset = new JumpResetFeature();
        AntiMissFeature antiMiss = new AntiMissFeature();
        BreachSwapFeature breachSwap = new BreachSwapFeature();
        TotemHitFeature totemHit = new TotemHitFeature();
        LungeSwapFeature lungeSwap = new LungeSwapFeature();

        SwordAttackCoordinator.install(antiMiss, breachSwap, totemHit, wTap, sTap);
        modules.register(aimAssist);
        modules.register(wTap);
        modules.register(sTap);
        modules.register(jumpReset);
        modules.register(antiMiss);
        modules.register(breachSwap);
        modules.register(totemHit);
        modules.register(lungeSwap);
    }
}
