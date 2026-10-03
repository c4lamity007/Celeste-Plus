package dev.c4lam.celeste.module.impl.combat;

/** No Minecraft mocks: exercises the same decisions, gates and lease used by runtime. */
public final class IndependentAuraRulesCheck {
    private static int assertions;
    public static void main(String[] args) {
        damage(); crystal(); confirmation(); ownership(); coordination(); swordFallback(); scopedRetirement();
        System.out.println("PASS AuraRules: " + assertions + " assertions");
    }

    private static void damage() {
        near(AuraRules.damage(0, 6, 1, 0, 0, 2), 85, "crystal center normal");
        near(AuraRules.damage(0, 5, 1, 0, 0, 2), 71, "anchor center normal");
        near(AuraRules.damage(6, 6, 1, 0, 0, 2), 32.5, "fractional damage is not floored");
        near(AuraRules.damage(6, 6, 1, 0, 0, 1), 17.25, "easy scaling");
        near(AuraRules.damage(6, 6, 1, 0, 0, 3), 48.75, "hard scaling");
        near(AuraRules.damage(6, 6, 1, 20, 8, 2), 17.0625, "armor and toughness order");
        near(AuraRules.damage(12, 6, 1, 0, 0, 2), 1, "inclusive explosion boundary");
        near(AuraRules.damage(Math.nextUp(12.0), 6, 1, 0, 0, 2), 0, "outside radius");
        near(AuraRules.damage(1, 6, 0, 0, 0, 2), 1, "zero exposure vanilla base point");
        near(AuraRules.damage(0, 6, 1, 0, 0, 0), 0, "peaceful");
        near(AuraRules.protectedDamage(10, 8), 6.8, "visible blast protection");
        near(AuraRules.protectedDamage(10, 100), 2, "protection cap");
        near(AuraRules.protectedDamage(10, -1), 10, "negative protection");
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            near(AuraRules.damage(bad, 6, 1, 0, 0, 2), 0, "invalid distance");
            near(AuraRules.damage(1, bad, 1, 0, 0, 2), 0, "invalid power");
            near(AuraRules.damage(1, 6, bad, 0, 0, 2), 0, "invalid exposure");
            check(!AuraRules.safe(8, 4, bad, 4, 6, false), "invalid safety health");
        }
        for (int power : new int[] {5, 6}) for (int armor : new int[] {0, 10, 20}) {
            double previous = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 80; step++) {
                double value = AuraRules.damage(step * power / 40.0, power, 0.73, armor, 8, 2);
                check(Double.isFinite(value) && value >= 0 && value <= previous + 1e-7, "bounded decreasing distance curve");
                previous = value;
            }
        }
        check(AuraRules.safe(4, 6, 8.01, 4, 6, false), "inclusive min/max, strict health margin");
        check(!AuraRules.safe(4, 6, 8, 4, 6, false), "two HP margin cannot be exhausted");
        check(!AuraRules.safe(3.99, 1, 20, 4, 6, false), "minimum target damage");
        check(!AuraRules.safe(8, 6.01, 20, 4, 6, false), "maximum self damage");
        check(!AuraRules.safe(8, 1, 20, 4, 6, true), "friend exclusion");
        check(!AuraRules.safe(0, 0, 20, 0, 6, false), "no pointless explosion");
    }

    private static void crystal() {
        check(!AuraRules.swordReady(5, 1.6), "full empty-hand charge is not full silent sword charge");
        check(!AuraRules.swordReady(11, 1.6), "sword recovery below92percent");
        check(AuraRules.swordReady(12, 1.6), "sword recovery at96percent");
        check(AuraRules.swordReady(46, 0.4) && !AuraRules.swordReady(45, 0.4), "slow modified weapon boundary");
        check(!AuraRules.swordReady(-1, 1.6) && !AuraRules.swordReady(20, Double.NaN), "invalid recovery inputs");
        eq(AuraRules.crystalAction(64, 64, false, true), AuraRules.CrystalAction.SWORD, "same feet sword");
        eq(AuraRules.crystalAction(64, 64, false, false), AuraRules.CrystalAction.PLACE, "sword cooldown does not block a separately safe crystal placement");
        eq(AuraRules.crystalAction(64, 64.1, false, true), AuraRules.CrystalAction.SWORD, "tiny motion not lift");
        eq(AuraRules.crystalAction(64, 64.2, false, false), AuraRules.CrystalAction.PLACE, "observed lift permits placement");
        eq(AuraRules.crystalAction(64, 64.2, true, false), AuraRules.CrystalAction.BREAK, "observed crystal permits break");
        eq(AuraRules.crystalAction(64, 63, false, true), AuraRules.CrystalAction.PLACE, "lower target can still take a safe crystal");
        eq(AuraRules.crystalAction(64, 64, true, true), AuraRules.CrystalAction.BREAK, "existing crystal beats same-height sword pressure");
        eq(AuraRules.crystalAction(64, 63, true, false), AuraRules.CrystalAction.BREAK, "existing crystal does not require target lift");
        eq(AuraRules.crystalAction(64, Double.NaN, false, true), AuraRules.CrystalAction.NONE, "invalid target");
        // Height decides sword pressure only. Permission to place/explode still
        // requires the independent live blast/range/friend/world checks.
        check(!AuraRules.safe(10, 21, 20, 4, 6, false), "placement option never makes an unsafe blast safe");
    }

    private static void confirmation() {
        var gate = new AuraRules.Confirmation();
        gate.begin(10, 100);
        eq(gate.observe(11, 100, true), AuraRules.ConfirmationResult.WAITING, "same tick cannot confirm");
        eq(gate.observe(10, 101, true), AuraRules.ConfirmationResult.WAITING, "prediction lacks server revision");
        eq(gate.observe(11, 102, false), AuraRules.ConfirmationResult.WAITING, "rollback mismatch waits");
        eq(gate.observe(11, 103, true), AuraRules.ConfirmationResult.CONFIRMED, "new server state reconciled");
        check(!gate.active(), "confirmed gate clears");
        gate.begin(11, 104);
        eq(gate.observe(11, 105, true), AuraRules.ConfirmationResult.WAITING, "retry cannot reuse old acknowledgement");
        eq(gate.observe(12, 127, false), AuraRules.ConfirmationResult.WAITING, "23 tick pending boundary");
        eq(gate.observe(12, 128, true), AuraRules.ConfirmationResult.TIMED_OUT, "late match must not revive timeout");
        check(!gate.active(), "timeout clears");
        gate.begin(12, 130);
        eq(gate.observe(13, 129, true), AuraRules.ConfirmationResult.TIMED_OUT, "clock rollback invalidates");
        gate.begin(13, 140); gate.clear();
        check(!gate.active(), "disable/world loss clears confirmation");
        gate.begin(14, 150);
        // A transient inventory screen does not call clear; pending still needs a new matching update.
        check(gate.active(), "pending survives a transient pause");
        eq(gate.observe(14, 153, true), AuraRules.ConfirmationResult.WAITING, "pause does not approve prediction");
        eq(gate.observe(15, 154, true), AuraRules.ConfirmationResult.CONFIRMED, "pause resumes server confirmation");
        gate.begin(160, 160, 8);
        eq(gate.observe(167, 167, false), AuraRules.ConfirmationResult.WAITING, "sword lift may arrive across several server ticks");
        eq(gate.observe(168, 168, false), AuraRules.ConfirmationResult.TIMED_OUT, "blocked sword cannot reserve whole CPVP for24ticks");
    }

    private static void coordination() {
        Object crystal = new Object(), anchor = new Object();
        for (boolean anchorFirst : new boolean[] {false, true}) {
            var turns = new dev.c4lam.celeste.action.SequenceTurnBroker();
            if (anchorFirst) { turns.register(anchor, 1, 5); turns.register(crystal, 0, 6); }
            else { turns.register(crystal, 0, 6); turns.register(anchor, 1, 5); }
            near(turns.maximumRange(), 6, "shared target uses enabled search ranges");
            turns.target(45);
            check(turns.target() == 45, "one stable shared target");
            long tick = 1;
            for (int sequence = 0; sequence < 8; sequence++) {
                Object expected = sequence % 2 == 0 ? crystal : anchor;
                Object other = expected == crystal ? anchor : crystal;
                // Every fresh crystal sequence can contain sword, base, place,
                // break; anchor sequences contain place, charge, break.
                int actions = expected == crystal ? 4 : 3;
                for (int step = 0; step < actions; step++) {
                    if (anchorFirst) { turns.offer(anchor, tick, true, null, 170); turns.offer(crystal, tick, true, null, 170); }
                    else { turns.offer(crystal, tick, true, null, 170); turns.offer(anchor, tick, true, null, 170); }
                    check(turns.winner(tick) == expected, "stable fair winner independent of registration/start order");
                    check(turns.begin(expected, tick), "chosen aura starts/continues an action");
                    check(!turns.begin(expected, tick) && !turns.begin(other, tick), "no two aura actions in one tick");
                    check(turns.owns(expected) && !turns.available(other), "complete explosive chain is exclusive");
                    tick++;
                    turns.offer(expected, tick, false, null, 170); turns.offer(other, tick, true, null, 170);
                    check(turns.winner(tick) == null, "pending server state/cooldown/pause cannot interleave second aura");
                    tick++;
                }
                turns.finish(expected);
                turns.offer(anchor, tick, true, null, 170); turns.offer(crystal, tick, true, null, 170);
                check(turns.winner(tick) == other, "completion yields to other ready aura");
            }
            turns.remove(anchor);
            turns.offer(crystal, tick, true, null, 170);
            check(turns.winner(tick) == crystal, "disabling one leaves standalone crystal working");
            check(turns.begin(crystal, tick), "standalone starts");
            turns.remove(crystal);
            check(turns.target() == -1 && turns.maximumRange() == 0, "world/disable clears target and enabled owners");
            check(turns.available(anchor) && turns.winner(tick + 1) == null, "disconnect leaves no ghost lease or plan");
        }
        var fallback = new dev.c4lam.celeste.action.SequenceTurnBroker();
        fallback.register(anchor, 1, 6); fallback.register(crystal, 0, 6);
        fallback.offer(anchor, 1, true, null, 170); fallback.offer(crystal, 1, false, null, 170);
        check(fallback.winner(1) == anchor && fallback.begin(anchor, 1), "unavailable crystal equipment/placement does not reserve a turn");
        fallback.finish(anchor);
        fallback.offer(anchor, 2, true, null, 170); fallback.offer(crystal, 2, false, null, 170);
        check(fallback.winner(2) == anchor, "no mandatory empty alternating turn");
        check(fallback.winner(3) == null, "stale plan cannot win a future tick");
        fallback.offer(crystal, 3, true, null, 170);
        check(fallback.begin(crystal, 3), "crystal joins after equipment becomes available");
        fallback.target(-1); fallback.finish(crystal);
        fallback.offer(crystal, 4, false, null, 170); fallback.offer(anchor, 4, true, null, 170);
        check(fallback.winner(4) == anchor, "target loss/timeout releases other aura without a fake confirmation");
        fallback.remove(crystal); fallback.remove(anchor);
        fallback.register(anchor, 1, 5); fallback.offer(anchor, 5, true, null, 170);
        check(fallback.winner(5) == anchor, "reenable starts clean standalone anchor");
        check(!fallback.begin(null, 5), "null owner rejected");
    }

    private static void swordFallback() {
        Object crystal = new Object(), anchor = new Object();
        var turns = new dev.c4lam.celeste.action.SequenceTurnBroker();
        var observation = new AuraRules.Confirmation();
        turns.register(crystal, 0, 6); turns.register(anchor, 1, 6);
        turns.offer(crystal, 1, true, null, 170); turns.offer(anchor, 1, true, null, 170);
        check(AuraRules.swordPressure(64, 64, true, turns.owns(crystal)), "initial grounded target permits sword pressure");
        check(turns.begin(crystal, 1), "sword starts crystal sequence");
        observation.begin(1, 1, 8);
        for (int tick = 2; tick < 9; tick++) {
            var waiting = observation.observe(tick, tick, false);
            check(!AuraRules.endsSequence(true, false, waiting), "waiting sword observation retains exclusive turn");
        }
        var noLift = observation.observe(9, 9, false);
        eq(noLift, AuraRules.ConfirmationResult.TIMED_OUT, "shield/ceiling stops lift observation");
        check(!AuraRules.endsSequence(true, false, noLift), "failed lift alone is not an explosive rejection");
        turns.offer(crystal, 9, true, null, 170); turns.offer(anchor, 9, true, null, 170);
        check(turns.winner(9) == crystal, "same crystal sequence gets its safe-placement fallback");
        check(!AuraRules.swordPressure(64, 64, true, turns.owns(crystal)), "fallback cannot repeat sword on shield forever");
        check(!AuraRules.safe(9, 12, 20, 4, 6, false), "no-lift fallback still rejects unsafe explosion");
        check(turns.begin(crystal, 9), "independently safe placement can follow without fabricated lift ACK");
        observation.begin(10, 9);
        var rejected = observation.observe(10, 33, false);
        check(AuraRules.endsSequence(false, false, rejected), "actual block/entity confirmation timeout releases turn");
        if (AuraRules.endsSequence(false, false, rejected)) turns.finish(crystal);
        turns.offer(crystal, 33, true, null, 170); turns.offer(anchor, 33, true, null, 170);
        check(turns.winner(33) == anchor, "other aura runs after failed explosive follow-up");
        check(AuraRules.endsSequence(false, true, AuraRules.ConfirmationResult.CONFIRMED), "confirmed detonation completes sequence");
        check(!AuraRules.endsSequence(false, false, AuraRules.ConfirmationResult.CONFIRMED), "confirmed preparation continues sequence");
        check(!AuraRules.endsSequence(false, true, AuraRules.ConfirmationResult.WAITING), "pending detonation is not completion");
    }

    private static void ownership() {
        var arm = new AuraRules.ActionArm();
        check(!arm.ready(1, true, false, false), "no first-frame interaction");
        arm.observeEnd(10, true);
        check(!arm.ready(10, true, false, false), "look must complete prior tick");
        check(arm.ready(11, true, false, false), "owned prior look arms action");
        check(!arm.ready(11, false, false, false), "higher priority owner preempts");
        check(!arm.ready(11, true, true, false), "manual input preempts");
        check(!arm.ready(11, true, false, true), "Silent Totem/other slot preempts");
        check(!arm.ready(12, true, false, false), "stale prior tick cannot arm");
        arm.observeEnd(12, false);
        check(!arm.ready(13, true, false, false), "lost end ownership resets");
        arm.observeEnd(13, true); arm.clear();
        check(!arm.ready(14, true, false, false), "disable/reset disarms");
        var lease = new AuraRules.PendingLease();
        Object crystal = new Object(), anchor = new Object();
        check(lease.available(crystal) && lease.acquire(crystal), "first aura takes pending lease");
        check(lease.available(crystal) && !lease.available(anchor) && !lease.acquire(anchor), "other aura cannot overlap unconfirmed damage");
        lease.release(anchor);
        check(!lease.available(anchor), "another owner cannot release pending lease");
        lease.release(crystal);
        check(lease.acquire(anchor), "confirmation/timeout releases for next aura");
        lease.release(anchor);
        check(lease.available(crystal) && !lease.acquire(null), "clear and invalid owner");
    }

    private static void scopedRetirement() {
        var turns = new dev.c4lam.celeste.action.SequenceTurnBroker();
        boolean[] allowed = {false};
        var scope = new dev.c4lam.celeste.module.delivery.ModuleScope("fixture", () -> allowed[0], () -> true, Runnable::run);
        Object owner = new Object();
        try (var context = scope.enter()) { turns.register(owner, 0, 6); }
        turns.offer(owner, 1, true, new dev.c4lam.celeste.rotation.Rotation(), 170);
        check(turns.winner(1) == null, "initializing owner never dispatches");
        allowed[0] = true;
        turns.offer(owner, 2, true, new dev.c4lam.celeste.rotation.Rotation(), 170);
        check(turns.winner(2) == owner && turns.begin(owner, 2), "active owner runs");
        check(dev.c4lam.celeste.rotation.RotationManager.getInstance().owners.contains(owner), "owned look installed");
        scope.close();
        check(turns.maximumRange() == 0 && turns.target() == -1 && turns.winner(3) == null, "scope retirement clears whole turn and target");
        check(!dev.c4lam.celeste.rotation.RotationManager.getInstance().owners.contains(owner), "scope retirement releases owned look");
        check(scope.retired().toCompletableFuture().isDone(), "scope drains without residual sequence owner");
        var unavailable = new dev.c4lam.celeste.module.delivery.ModuleScope(() -> false);
        try (var context = unavailable.enter()) { turns.register(new Object(), 0, 6); }
        check(turns.maximumRange() == 0, "expired registration adds no owner");
        unavailable.close();
    }

    private static void eq(Object actual, Object expected, String label) { check(actual.equals(expected), label + ": " + actual); }
    private static void near(double actual, double expected, String label) { check(Math.abs(actual - expected) < 1e-6, label + ": " + actual); }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
