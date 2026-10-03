package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.hud.LocalDamageEstimator;

/** Stateless vanilla mace ranking; no inventory, cooldown or target state is changed. */
final class MaceSelection {
    private static final double TIE_EPSILON = 1.0E-7;

    private MaceSelection() {
    }

    static double cooldown(int elapsedTicks, double attackSpeed) {
        if (elapsedTicks < 0 || !Double.isFinite(attackSpeed) || attackSpeed <= 0.0) return 0.0;
        return Math.min(1.0, (elapsedTicks + 0.5) * attackSpeed / 20.0);
    }

    static double damage(double baseDamage, double enchantmentDamage, double fallDistance,
                         boolean smashAllowed, int density, int breach, double armor,
                         double toughness, double cooldown, boolean critical) {
        // Reuse only the HUD model's stateless vanilla math, never its health ledger.
        // 1.21.11: Density adds .5/level/block; Breach subtracts .15/level
        // from armor effectiveness AFTER vanilla damage/toughness reduction.
        double smash = smashAllowed ? LocalDamageEstimator.maceBonus(fallDistance, density) : 0.0;
        double outgoing = LocalDamageEstimator.outgoing(baseDamage, enchantmentDamage, smash, cooldown, critical);
        // Protection/resistance multiply both candidates equally, so cannot change their order.
        return LocalDamageEstimator.mitigate(outgoing,
            new LocalDamageEstimator.Defense(armor, toughness, 0.0, 0.15 * Math.max(0, breach)));
    }

    /** NaN denotes a non-mace. Exact damage ties keep the held mace, then hotbar order. */
    static int bestSlot(double[] damage, int visible) {
        int best = -1;
        double bestDamage = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < Math.min(9, damage.length); slot++) {
            double candidate = damage[slot];
            if (!Double.isFinite(candidate) || candidate < 0.0) continue;
            if (best < 0 || candidate > bestDamage + TIE_EPSILON
                || Math.abs(candidate - bestDamage) <= TIE_EPSILON && slot == visible) {
                best = slot;
                bestDamage = candidate;
            }
        }
        return best;
    }
}
