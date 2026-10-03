package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.UseInputLease;
import dev.c4lam.celeste.gui.model.GuiCatalog;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.silent.SilentSlotManager;
import java.util.List;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager.Event;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Real coordinator/scheduler/slot execution against independent prediction/server traces. */
@SuppressWarnings("try")
public final class IndependentCombatSequenceCheck {
    private static int checks;
    private IndependentCombatSequenceCheck() { }
    public static void main(String[] args) {
        for (boolean silent : new boolean[] {false, true})
            for (boolean acknowledge : new boolean[] {false, true})
                for (boolean unsupported : new boolean[] {false, true})
                    for (double chance : new double[] {0, 100}) flow(silent, acknowledge, unsupported, chance);
        predictionDoesNotReplay();
        rejectedServerDoesNotReplay();
        holdRearmsOnlyOnRelease();
        holdToLoopUsesNormalCycles();
        normalModuleUnaffected();
        modifierCanRearmBetweenNormalCycles();
        retirementCancelsQueuedSequence();
        retirementAfterUsePreservesPacketOrder();
        repeatedModifierPressRearms();
        closedScopeCannotRegister();
        authenticatedStartupRegistration();
        interruptionStops();
        System.out.println("PASS Double Anchor five-tick behavior: " + checks
            + " checks; independent controllers/host broker/slot manager/scheduler, bounded prediction/server trace, no live server claim");
    }

    private static void flow(boolean silent, boolean acknowledge, boolean unsupported, double chance) {
        Fixture f = new Fixture(true, silent, acknowledge, chance);
        f.tick();
        if (unsupported) {
            f.client.world.blocks.put(f.support, Blocks.AIR.getDefaultState());
            f.client.interactionManager.serverBlocks.put(f.support, Blocks.AIR.getDefaultState());
        }
        f.untilUses(4, 12);
        check(f.uses().get(2).tick() == f.uses().get(3).tick(), "First blast and replacement share one tick");
        check(f.uses().get(2).sequence() + 1 == f.uses().get(3).sequence(), "Each tick-3 use has a distinct consecutive sequence");
        check(f.uses().get(2).hit().equals(f.placed) && f.uses().get(3).hit().equals(f.placed),
            "Both tick-3 uses target the same original anchor space");
        if (!acknowledge) {
            check(f.client.world.getBlockState(f.placed).isOf(Blocks.RESPAWN_ANCHOR)
                && f.client.world.getBlockState(f.placed).charges() == 1,
                "Stale client anchor is not removed or manufactured to force prediction");
            check(f.client.interactionManager.events.get(3).action().equals("explode"),
                "Second prediction still sees the old charged anchor while server sees replacement");
        }
        f.untilUses(6, 12);
        assertPair(f, chance);
        check(f.client.interactionManager.serverBlocks.get(f.placed).isAir(), "Server trace finishes second detonation");
        f.client.interactionManager.acknowledge();
        f.ticks(7);
        check(f.uses().size() == 6, "Delayed completion/update does not repeat any use");
        check(f.client.interactionManager.vanillaUses == 0, "Consumed hold cannot leak an extra vanilla use");
        check(f.client.interactionManager.orderViolations.isEmpty(), "No held slot update after a USE in the same tick");
        if (silent) check(f.client.player.inventory.selected == 0, "Silent sequence preserves visible selected slot");
        f.client.useHeld = false;
        f.tick();
        check(!UseInputLease.getInstance().owns(dev.c4lam.celeste.action.HeldUseBroker.INSTANCE), "Physical release ends use ownership");
    }

    private static void assertPair(Fixture f, double chance) {
        check(f.uses().stream().map(Event::action).toList().equals(List.of("place", "charge", "explode", "place", "charge", "explode")),
            "Server receives exact pair: " + f.uses());
        check(f.uses().stream().map(Event::slot).toList().equals(List.of(0, 1, 0, 0, 1, 2)), "Exact anchor/glowstone/anchor/anchor/glowstone/totem items");
        long first = f.uses().getFirst().tick();
        List<Long> expected = chance == 0 ? List.of(0L, 1L, 2L, 2L, 3L, 4L) : List.of(0L, 2L, 4L, 4L, 6L, 8L);
        check(f.uses().stream().map(event -> event.tick() - first).toList().equals(expected),
            "Swap delay affects stages without splitting the tick-3 pair: " + f.uses());
        check(f.client.interactionManager.sequence == 6, "Six independent predicted interaction sequences");
    }

    private static void predictionDoesNotReplay() {
        for (int attempt : new int[] {4, 5, 6}) for (boolean pass : new boolean[] {false, true}) {
            Fixture f = new Fixture(true, true, false, 0);
            (pass ? f.client.interactionManager.passAttempts : f.client.interactionManager.failAttempts).add(attempt);
            f.ticks(12);
            assertPair(f, 0);
            check(f.client.interactionManager.vanillaUses == 0, "FAIL/PASS prediction does not leak native use");
            check(f.client.interactionManager.orderViolations.isEmpty(), "FAIL/PASS prediction preserves slot order");
        }
        for (boolean pass : new boolean[] {false, true}) {
            Fixture f = new Fixture(true, true, false, 0);
            (pass ? f.client.interactionManager.passAttempts : f.client.interactionManager.failAttempts).add(3);
            f.ticks(12);
            check(f.uses().size() == 3, "Rejected first detonation prediction stops before replacement");
            check(f.client.interactionManager.vanillaUses == 0, "Rejected first detonation retains use ownership");
        }
        for (int attempt : new int[] {3, 4, 5, 6}) {
            Fixture f = new Fixture(true, true, false, 0);
            f.client.interactionManager.throwAttempts.add(attempt);
            f.ticks(12);
            check(f.client.interactionManager.events.size() == attempt, "Prediction exception stops without replay");
            check(f.uses().size() == attempt - 1, "Exception before packet construction emits no invented USE");
            check(f.client.interactionManager.orderViolations.isEmpty(), "Exception cleanup keeps slot ordering");
            check(f.client.interactionManager.vanillaUses == 0, "Exception consumes held native use until release");
        }
    }

    private static void rejectedServerDoesNotReplay() {
        Fixture f = new Fixture(true, false, false, 0);
        f.client.interactionManager.rejectServerReplacement = true;
        f.ticks(12);
        check(f.uses().size() == 6, "No acknowledgement-dependent retries after rejected replacement");
        check(f.uses().get(3).action().equals("rejected-place"), "Server rejection is represented independently");
        check(f.uses().stream().filter(event -> event.action().equals("charge")).count() == 1,
            "Fixture does not claim second charge succeeded after server rejected placement");
        f.client.interactionManager.acknowledge(); f.ticks(6);
        check(f.uses().size() == 6, "Late rejection does not replay completed transaction");
    }

    private static void holdRearmsOnlyOnRelease() {
        Fixture f = new Fixture(true, false, true, 0);
        f.ticks(10); check(f.uses().size() == 6, "One pair per initial modifier hold");
        f.newUse(); f.ticks(8);
        check(f.uses().subList(6, f.uses().size()).stream().map(Event::slot).toList().equals(List.of(0, 1, 2)),
            "New RMB use while modifier stays held runs a normal anchor cycle");
        f.modifier.held = false; f.client.useHeld = false; f.tick();
        f.modifier.held = true; f.client.useHeld = true; f.aimSupport(); f.ticks(10);
        check(f.uses().subList(9, f.uses().size()).stream().map(Event::slot).toList().equals(List.of(0, 1, 0, 0, 1, 2)),
            "Observed modifier release rearms exactly one pair");
    }

    private static void normalModuleUnaffected() {
        Fixture f = new Fixture(false, false, true, 0);
        f.ticks(10);
        check(f.uses().stream().map(Event::slot).toList().equals(List.of(0, 1, 2)), "Unheld modifier preserves normal three-action Anchor Macro");
        check(f.uses().get(1).tick() - f.uses().get(0).tick() == 1
            && f.uses().get(2).tick() - f.uses().get(1).tick() == 1, "Normal sequence timing stays unchanged");
    }

    private static void holdToLoopUsesNormalCycles() {
        for (boolean silent : new boolean[] {false, true}) {
            Fixture f = new Fixture(true, silent, true, 0);
            f.anchor.toggles.put("Hold to loop", true);
            f.untilUses(12, 24);
            check(f.uses().subList(0, 6).stream().map(Event::slot).toList().equals(List.of(0, 1, 0, 0, 1, 2)),
                "Held loop begins with exactly one double pair");
            check(f.uses().subList(6, 12).stream().map(Event::slot).toList().equals(List.of(0, 1, 2, 0, 1, 2)),
                "Held loop returns to normal Anchor Macro while modifier remains held");
            check(f.client.interactionManager.vanillaUses == 0 && f.client.interactionManager.orderViolations.isEmpty(),
                "Held loop retains exclusive input and correct slot order");
        }
    }

    private static void interruptionStops() {
        for (String kind : List.of("release", "disable", "missing-glowstone", "border", "unloaded")) {
            Fixture f = new Fixture(true, true, false, 0);
            f.untilUses(4, 8);
            switch (kind) {
                case "release" -> f.client.useHeld = false;
                case "disable" -> { f.modifier.enabled = false; f.coordinator.onDoubleAnchorDisabled(f.client); }
                case "missing-glowstone" -> f.client.player.inventory.stacks[1] = ItemStack.EMPTY;
                case "border" -> f.client.world.insideBorder = false;
                case "unloaded" -> f.client.world.loaded = false;
                default -> throw new AssertionError(kind);
            }
            f.ticks(6);
            check(f.uses().size() == 4, "Invalid continuation stops after atomic pair: " + kind);
            check(f.client.interactionManager.orderViolations.isEmpty(), "Interrupted continuation preserves slot ordering: " + kind);
        }
    }

    private static void modifierCanRearmBetweenNormalCycles() {
        Fixture f = new Fixture(false, true, true, 0);
        f.anchor.toggles.put("Hold to loop", true);
        f.untilUses(3, 10);
        f.modifier.held = true;
        f.untilUses(9, 15);
        check(f.uses().subList(3, 9).stream().map(Event::slot).toList().equals(List.of(0, 1, 0, 0, 1, 2)),
            "A fresh modifier hold takes over at the next normal-cycle boundary");
        check(f.uses().get(3).tick() - f.uses().get(2).tick() == 2,
            "Policy handoff preserves the normal two-tick cycle gap");
    }

    private static void retirementCancelsQueuedSequence() {
        Fixture f = new Fixture(true, true, true, 0);
        f.untilUses(2, 10);
        ActionScheduler.getInstance().beginTick(f.client); SilentSlotManager.getInstance().beginTick(f.client);
        f.coordinator.onStartTick(f.context); f.coordinator.onActionTick(f.context);
        int before = f.uses().size();
        f.coordinator.modifierScope.close();
        ActionScheduler.getInstance().flush(f.client);
        check(f.uses().size() == before, "Retirement cancels already queued payload actions");
        check(f.coordinator.modifierScope.retired().toCompletableFuture().isDone(), "Controller registration drains on retirement");
        f.ticks(3);
        check(f.uses().size() == before, "Retired paired sequence cannot resume or leak vanilla use");
    }

    private static void retirementAfterUsePreservesPacketOrder() {
        Fixture f = new Fixture(true, true, true, 0);
        f.tick();
        f.client.crosshairTarget = new BlockHitResult(Vec3d.ofCenter(f.placed), Direction.UP, f.placed, false);
        ActionScheduler.getInstance().beginTick(f.client); SilentSlotManager.getInstance().beginTick(f.client);
        f.coordinator.onStartTick(f.context); f.coordinator.onActionTick(f.context); ActionScheduler.getInstance().flush(f.client);
        check(f.uses().size() == 2 && f.uses().getLast().slot() == 1, "Retirement fixture sent glowstone under a hidden slot");
        f.coordinator.modifierScope.close();
        check(f.client.interactionManager.orderViolations.isEmpty(), "Post-use scope retirement does not send a same-tick held-slot restore");
        check(SilentSlotManager.getInstance().isActive(), "Resident deferred restore survives payload retirement");
        f.client.interactionManager.movement(); SilentSlotManager.getInstance().endTick(f.client);
        f.tick();
        check(!SilentSlotManager.getInstance().isActive(), "Deferred slot restore completes on the next tick");
        check(f.client.interactionManager.orderViolations.isEmpty(), "Deferred restore stays after the movement boundary");
    }

    private static void repeatedModifierPressRearms() {
        Fixture f = new Fixture(true, true, true, 0);
        f.anchor.toggles.put("Hold to loop", true);
        f.untilUses(9, 20);
        f.modifier.held = false; f.tick(); f.modifier.held = true;
        f.untilUses(15, 20);
        check(f.uses().subList(9,15).stream().map(Event::slot).toList().equals(List.of(0,1,0,0,1,2)),
            "A later physical modifier press can pair again while RMB stays held");
    }

    private static void closedScopeCannotRegister() {
        var scope = new dev.c4lam.celeste.module.delivery.ModuleScope();
        try (var entered = scope.enter()) {
            scope.close();
            boolean rejected = false;
            try { dev.c4lam.celeste.action.HeldUseBroker.INSTANCE.register(new AnchorController(), 300); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "Retiring scope cannot attach another combat controller");
        }
    }

    private static void authenticatedStartupRegistration() {
        var started=new java.util.concurrent.atomic.AtomicBoolean(false);
        var valid=new java.util.concurrent.atomic.AtomicBoolean(true);
        var scope=new dev.c4lam.celeste.module.delivery.ModuleScope("startup", ()->valid.get()&&started.get(),valid::get,Runnable::run);
        int[] invoked={0};
        var policy=new dev.c4lam.celeste.action.HeldUseBroker.Policy() {
            public void start(ModuleContext context) { invoked[0]++; }
            public void action(ModuleContext context) { invoked[0]++; }
            public void stop(MinecraftClient client) { }
            public boolean wantsUse() { invoked[0]++; return false; }
        };
        var broker=dev.c4lam.celeste.action.HeldUseBroker.INSTANCE;
        try(var context=scope.enter()) { broker.register(policy,1); }
        check(!broker.authorized(policy),"Inert controller registration does not activate callback before onEnable returns");
        broker.run(policy,()->invoked[0]++);check(invoked[0]==0,"Registered startup controller cannot execute yet");
        started.set(true);broker.run(policy,()->invoked[0]++);check(invoked[0]==1,"Controller executes after successful activation");
        valid.set(false);
        try(var context=scope.enter()) {
            boolean rejected=false;
            try { broker.register(policy,2); } catch(IllegalStateException expected) { rejected=true; }
            check(rejected,"Expired authenticated startup cannot register another controller");
        }
        broker.run(policy,()->invoked[0]++);check(invoked[0]==1,"Expired controller dispatch stays blocked");
        scope.close();
    }

    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final class Fixture {
        final MinecraftClient client = new MinecraftClient();
        final GuiModule anchor = new GuiModule("Anchor Macro", true), modifier = new GuiModule("Double Anchor Macro", true);
        final ModuleContext context = new ModuleContext(client, anchor);
        final Harness coordinator = new Harness();
        final BlockPos support = new BlockPos(0, 0, 2), placed = support.up();
        final boolean autoAcknowledge;
        Fixture(boolean held, boolean silent, boolean acknowledge, double chance) {
            autoAcknowledge = acknowledge;
            GuiCatalog.modules.clear(); GuiCatalog.modules.add(anchor); GuiCatalog.modules.add(modifier);
            anchor.toggles.put("Silent swap", silent); anchor.sliders.put("2 tick swap chance", chance);
            client.player.inventory.stacks[0] = new ItemStack(Items.RESPAWN_ANCHOR, 16);
            client.player.inventory.stacks[1] = new ItemStack(Items.GLOWSTONE, 16);
            client.player.inventory.stacks[2] = new ItemStack(Items.TOTEM_OF_UNDYING);
            client.world.blocks.put(support, Blocks.STONE.getDefaultState());
            client.interactionManager.serverBlocks.putAll(client.world.blocks);
            aimSupport();
            ActionScheduler.getInstance().clear(); UseInputLease.getInstance().clear(client); SilentSlotManager.getInstance().clear();
            coordinator.onWorldChanged(client);
            modifier.held = false; client.useHeld = false; tick();
            modifier.held = held; client.useHeld = true;
        }
        List<Event> uses() { return client.interactionManager.serverEvents; }
        void aimSupport() { client.crosshairTarget = new BlockHitResult(Vec3d.ofCenter(support), Direction.UP, support, false); }
        void tick() {
            if (client.world.getBlockState(placed).isOf(Blocks.RESPAWN_ANCHOR))
                client.crosshairTarget = new BlockHitResult(Vec3d.ofCenter(placed), Direction.UP, placed, false);
            else if (!client.world.getBlockState(support).isAir()) aimSupport();
            client.options.useKey.pressed = client.useHeld;
            client.options.useKey.queued = client.useHeld ? 2 : 0;
            ActionScheduler.getInstance().beginTick(client); SilentSlotManager.getInstance().beginTick(client);
            coordinator.onStartTick(context); coordinator.onActionTick(context); ActionScheduler.getInstance().flush(client);
            client.interactionManager.simulateVanillaUse();
            client.interactionManager.movement(); SilentSlotManager.getInstance().endTick(client);
            if (autoAcknowledge) client.interactionManager.acknowledge();
        }
        void ticks(int count) { for (int i = 0; i < count; i++) tick(); }
        void untilUses(int count, int limit) {
            for (int i = 0; i < limit && uses().size() < count; i++) tick();
            check(uses().size() == count, "Reach " + count + " uses: " + uses());
        }
        void newUse() { client.useHeld = false; tick(); client.useHeld = true; aimSupport(); }
    }
    private static final class Harness {
        private static final java.util.List<dev.c4lam.celeste.action.HeldUseBroker.Registration> PREVIOUS = new java.util.ArrayList<>();
        private final dev.c4lam.celeste.action.HeldUseBroker broker = dev.c4lam.celeste.action.HeldUseBroker.INSTANCE;
        private final dev.c4lam.celeste.module.delivery.ModuleScope anchorScope = new dev.c4lam.celeste.module.delivery.ModuleScope();
        private final dev.c4lam.celeste.module.delivery.ModuleScope modifierScope = new dev.c4lam.celeste.module.delivery.ModuleScope();
        private final AnchorController anchor = new AnchorController();
        private final DoubleAnchorController modifier = new DoubleAnchorController();
        private final dev.c4lam.celeste.action.HeldUseBroker.Registration modifierRegistration;
        Harness() {
            for (var registration : PREVIOUS) registration.close();
            PREVIOUS.clear();
            try (var entered = anchorScope.enter()) { PREVIOUS.add(broker.register(anchor, 300)); }
            try (var entered = modifierScope.enter()) { modifierRegistration = broker.register(modifier, 400); }
            PREVIOUS.add(modifierRegistration);
        }
        void onWorldChanged(MinecraftClient client) { anchor.stop(client); modifier.stop(client); }
        void onStartTick(ModuleContext context) { broker.onStartTick(context); }
        void onActionTick(ModuleContext context) { broker.onActionTick(context); }
        void onDoubleAnchorDisabled(MinecraftClient client) { modifierRegistration.close(); }
    }

}
