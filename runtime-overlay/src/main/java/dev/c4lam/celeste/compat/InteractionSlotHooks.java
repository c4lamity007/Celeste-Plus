package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.silent.SilentSlotManager;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;

/** Client-thread bodies shared by startup slot mixins and the optional late host. */
public final class InteractionSlotHooks {
    private static final ReferenceQueue<Object> RETIRED_MANAGERS = new ReferenceQueue<>();
    private static final Map<ManagerKey, Deque<Integer>> BREAKING_PROJECTIONS = new HashMap<>();

    private InteractionSlotHooks() { }

    /** Replaces only getSelectedSlot() inside vanilla syncSelectedSlot(). */
    public static int synchronizationSlot(PlayerInventory inventory) {
        return synchronizationSlot(inventory.getSelectedSlot());
    }

    public static int synchronizationSlot(int vanilla) {
        return SilentSlotManager.getInstance().serverSlotOr(vanilla);
    }

    /** Retire saved nesting state after interaction hooks and gameplay services stop. */
    public static void clear() {
        BREAKING_PROJECTIONS.clear();
        retireCollectedManagers();
    }

    /** At entry to attackBlock/updateBlockBreakingProgress, including nested invocations. */
    public static void beginBreaking(Object interactionManager) {
        Deque<Integer> projections = projections(interactionManager);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            projections.push(SilentSlotManager.getInstance()
                .beginLocalProjection(client.player.getInventory()));
        } else {
            projections.push(-1);
        }
    }

    /**
     * Paired exactly once with each completed beginBreaking: normal, canceled
     * and exceptional exits all release their own nesting token. Startup uses
     * a whole-method finally wrapper; the late transformer uses equivalent
     * return callbacks and exception cleanup, excluding callback bodies.
     */
    public static void endBreaking(Object interactionManager) {
        Objects.requireNonNull(interactionManager, "interactionManager");
        retireCollectedManagers();
        ManagerKey lookup = new ManagerKey(interactionManager);
        Deque<Integer> projections = BREAKING_PROJECTIONS.get(lookup);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            int previousSlot = projections == null || projections.isEmpty() ? -1 : projections.pop();
            if (projections != null && projections.isEmpty()) BREAKING_PROJECTIONS.remove(lookup);
            SilentSlotManager.getInstance().endLocalProjection(client.player.getInventory(), previousSlot);
        } else if (projections != null && !projections.isEmpty()) {
            projections.pop();
            if (projections.isEmpty()) BREAKING_PROJECTIONS.remove(lookup);
        }
    }

    private static Deque<Integer> projections(Object interactionManager) {
        Objects.requireNonNull(interactionManager, "interactionManager");
        retireCollectedManagers();
        ManagerKey lookup = new ManagerKey(interactionManager);
        Deque<Integer> projections = BREAKING_PROJECTIONS.get(lookup);
        if (projections == null) {
            projections = new ArrayDeque<>();
            BREAKING_PROJECTIONS.put(new ManagerKey(interactionManager, RETIRED_MANAGERS), projections);
        }
        return projections;
    }

    private static void retireCollectedManagers() {
        ManagerKey retired;
        while ((retired = (ManagerKey) RETIRED_MANAGERS.poll()) != null) BREAKING_PROJECTIONS.remove(retired);
    }

    /** Match the old per-object field without retaining disconnected managers or consulting equals(). */
    private static final class ManagerKey extends WeakReference<Object> {
        private final int identityHash;

        private ManagerKey(Object manager) {
            super(manager);
            identityHash = System.identityHashCode(manager);
        }

        private ManagerKey(Object manager, ReferenceQueue<Object> queue) {
            super(manager, queue);
            identityHash = System.identityHashCode(manager);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object manager = get();
            return manager != null && other instanceof ManagerKey key && manager == key.get();
        }
    }
}
