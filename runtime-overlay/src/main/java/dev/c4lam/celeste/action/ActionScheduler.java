package dev.c4lam.celeste.action;

import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Selects exactly one primary interaction for a client tick. Modules plan and
 * submit during START_CLIENT_TICK; the winner is executed in that same start
 * callback before Minecraft advances the player/world tick.
 */
public final class ActionScheduler {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/ActionScheduler");
    private static final ActionScheduler INSTANCE = new ActionScheduler();

    private final Map<Object, PendingAction> pending = new LinkedHashMap<>();
    private Object executedOwner;
    private long sequence;
    private long tick;
    private boolean executed;

    private ActionScheduler() {
    }

    public static ActionScheduler getInstance() {
        return INSTANCE;
    }

    public boolean submit(Object owner, int priority, PrimaryAction action) {
        requireClientThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(action, "action");
        if (executed) {
            return false;
        }

        PendingAction request = new PendingAction(owner, priority, action, false, ++sequence);
        pending.put(owner, request);
        return preferredCandidate() == request;
    }

    /**
     * Reserved for an explicitly modelled vanilla mounted action. Existing
     * modules intentionally use {@link #submit} and are conservatively gated.
     */
    public boolean submitMountedSafe(Object owner, int priority, PrimaryAction action) {
        requireClientThread();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(action, "action");
        if (executed) {
            return false;
        }
        PendingAction request = new PendingAction(owner, priority, action, true, ++sequence);
        pending.put(owner, request);
        return preferredCandidate() == request;
    }

    public void cancel(Object owner) {
        requireClientThread();
        pending.remove(owner);
    }

    public boolean hasPendingAction() {
        return !pending.isEmpty();
    }

    public boolean hasExecuted() {
        return executed;
    }

    public boolean executedFor(Object owner) {
        return executed && Objects.equals(executedOwner, owner);
    }

    public long tickNumber() {
        return tick;
    }

    public void beginTick(MinecraftClient client) {
        requireClientThread(client);
        tick++;
        pending.clear();
        executed = false;
        executedOwner = null;
    }

    public void flush(MinecraftClient client) {
        requireClientThread(client);
        if (executed || pending.isEmpty() || client.player == null || client.world == null) {
            return;
        }

        // Never combine an automated primary interaction with vanilla input
        // that Minecraft will process later in this same tick. Modules that
        // deliberately replace one physical use edge first suppress and drain
        // that binding (Wind Charge / Auto Firework), so they remain eligible.
        if (VanillaInputGuard.hasInteractionConflict(client)) {
            pending.clear();
            return;
        }

        RotationManager rotations = RotationManager.getInstance();
        PendingAction selected = pending.values().stream()
            .filter(candidate -> (!client.player.hasVehicle() || candidate.allowMounted))
            .filter(candidate -> rotations.permitsAction(candidate.owner))
            .max(ActionScheduler::compare)
            .orElse(null);
        pending.clear();
        if (selected == null || !rotations.alignForAction(selected.owner)) {
            return;
        }
        executed = true;
        executedOwner = selected.owner;
        try {
            selected.action.execute(client);
        } catch (RuntimeException exception) {
            LOGGER.error("Primary action from {} failed", selected.owner, exception);
        }
    }

    public void clear() {
        pending.clear();
        executed = false;
        executedOwner = null;
    }

    private static void requireClientThread() {
        requireClientThread(MinecraftClient.getInstance());
    }

    private static void requireClientThread(MinecraftClient client) {
        if (!client.isOnThread()) {
            throw new IllegalStateException("Gameplay actions must run on the Minecraft client thread");
        }
    }

    private PendingAction preferredCandidate() {
        return pending.values().stream().max(ActionScheduler::compare).orElse(null);
    }

    private static int compare(PendingAction left, PendingAction right) {
        int priority = Integer.compare(left.priority, right.priority);
        return priority != 0 ? priority : Long.compare(left.sequence, right.sequence);
    }

    private record PendingAction(Object owner, int priority, PrimaryAction action,
                                 boolean allowMounted, long sequence) {
    }
}
