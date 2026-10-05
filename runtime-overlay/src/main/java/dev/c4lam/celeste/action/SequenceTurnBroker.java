package dev.c4lam.celeste.action;
import java.util.Map;
import java.util.LinkedHashMap;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.rotation.MoveFixMode;
/** Generic whole-sequence turn arbitration and rotation offers. Payloads own all action planning. */
public final class SequenceTurnBroker {
        public static final SequenceTurnBroker INSTANCE = new SequenceTurnBroker();
        private final Map<Object, Offer> offers = new LinkedHashMap<>();
        private Object sequenceOwner;
        private Object lastOwner;
        private int target = -1;
        private long lastActionTick = Long.MIN_VALUE;

        private static final class Offer {
            final int tieRank;
            final ModuleScope scope;
            final java.util.concurrent.CompletableFuture<Void> detached = new java.util.concurrent.CompletableFuture<>();
            Rotation look;
            int priority;
            long tick = Long.MIN_VALUE;
            double range;
            Offer(int tieRank, ModuleScope scope) { this.tieRank = tieRank; this.scope = scope; }
        }

        public void register(Object owner, int tieRank, double range) {
            if (owner == null) return;
            ModuleScope current = ModuleScope.current();
            if (current != null && !current.canRegister()) return;
            Offer offer = offers.get(owner);
            if (offer == null) {
                ModuleScope scope = ModuleScope.current();
                offer = new Offer(tieRank, scope);
                offers.put(owner, offer);
                if (scope != null) scope.ownTransient(() -> remove(owner), offer.detached);
            }
            offer.range = Double.isFinite(range) ? Math.max(0, range) : 0;
        }

        public void offer(Object owner, long tick, boolean ready, Rotation look, int priority) {
            Offer offer = offers.get(owner);
            if (offer != null) { offer.tick = ready ? tick : Long.MIN_VALUE; offer.look = look; offer.priority = priority; }
            Object selected = winner(tick);
            for (var entry : offers.entrySet()) {
                Offer candidate = entry.getValue();
                if (entry.getKey() != selected || candidate.look == null || !allowed(candidate)) {
                    RotationManager.getInstance().release(entry.getKey()); continue;
                }
                Runnable publish = () -> RotationManager.getInstance().submit(RotationTask.builder(entry.getKey(), candidate.look)
                    .priority(candidate.priority).lifetimeTicks(1).smoothing(180, 180).sensitivityQuantized(false)
                    .moveFix(MoveFixMode.STRICT).build());
                if (candidate.scope == null) publish.run();
                else try (var context = candidate.scope.tryEnter()) { if (context != null) publish.run(); }
            }
        }

        public Object winner(long tick) {
            if (tick == lastActionTick) return null;
            if (sequenceOwner != null) {
                Offer offer = offers.get(sequenceOwner);
                return offer != null && allowed(offer) && offer.tick == tick ? sequenceOwner : null;
            }
            Object best = null;
            for (Map.Entry<Object, Offer> candidate : offers.entrySet()) {
                if (!allowed(candidate.getValue()) || candidate.getValue().tick != tick) continue;
                if (best == null || rank(candidate.getKey()) < rank(best)) best = candidate.getKey();
            }
            return best;
        }

        private int rank(Object owner) {
            return (owner == lastOwner ? 2 : 0) + offers.get(owner).tieRank;
        }

        public boolean begin(Object owner, long tick) {
            if (owner == null || winner(tick) != owner) return false;
            sequenceOwner = owner;
            lastActionTick = tick;
            return true;
        }

        public boolean owns(Object owner) { return owner != null && sequenceOwner == owner; }
        public boolean available(Object owner) { return sequenceOwner == null || sequenceOwner == owner; }
        public int target() { return target; }
        public void target(int id) { target = id; }
        public double maximumRange() {
            return offers.values().stream().filter(SequenceTurnBroker::allowed).mapToDouble(offer -> offer.range).max().orElse(0);
        }

        public void finish(Object owner) {
            if (sequenceOwner != owner) return;
            sequenceOwner = null;
            lastOwner = owner;
        }

        public void remove(Object owner) {
            finish(owner);
            Offer removed = offers.remove(owner);
            if (removed != null) removed.detached.complete(null);
            RotationManager.getInstance().release(owner);
            if (lastOwner == owner) lastOwner = null;
            if (offers.isEmpty()) { target = -1; sequenceOwner = null; lastOwner = null; }
        }
        private static boolean allowed(Offer offer) { return offer.scope == null || offer.scope.allowed(); }
    }
