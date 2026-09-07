package dev.c4lam.celeste.silent;

import dev.c4lam.celeste.compat.MinecraftAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;

import java.util.Objects;

/**
 * Client-thread-only hotbar policy. Silent leases keep the visible selection
 * untouched and restore the server selection through Minecraft's synchronizer;
 * explicit visible selection uses that same vanilla synchronization path.
 */
public final class SilentSlotManager {
    private static final SilentSlotManager INSTANCE = new SilentSlotManager();

    private Object owner;
    private int priority;
    private int requestedSlot = -1;
    private int snapshotSlot = -1;
    private int observedVisibleSlot = -1;
    private int assumedServerSlot = -1;
    private long tick;
    private long expiresAtTick;
    private boolean restorePending;
    private int localProjectionDepth;
    private ClientPlayerEntity playerInstance;
    private ClientPlayerEntity visibleActionPlayer;
    private int visibleActionOriginalSlot = -1;
    private int visibleActionSelectedSlot = -1;
    private long visibleRestoreAtTick;

    private SilentSlotManager() {
    }

    public static SilentSlotManager getInstance() {
        return INSTANCE;
    }

    public boolean acquire(Object requestedOwner, int slot, int requestedPriority, int holdTicks) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        Objects.requireNonNull(requestedOwner, "requestedOwner");
        if (slot < 0 || slot > 8 || holdTicks < 1 || client.player == null || client.world == null) {
            return false;
        }
        // Once an action has finished, keep its server slot pinned for the rest
        // of that tick. A second owner must not insert a held-item change between
        // the action and its deferred vanilla restore.
        if (restorePending) {
            return false;
        }

        int visibleSlot = client.player.getInventory().getSelectedSlot();
        if (owner == null) {
            owner = requestedOwner;
            priority = requestedPriority;
            requestedSlot = slot;
            snapshotSlot = visibleSlot;
            observedVisibleSlot = visibleSlot;
        } else if (Objects.equals(owner, requestedOwner)) {
            priority = requestedPriority;
            requestedSlot = slot;
        } else if (requestedPriority > priority) {
            // Deliberately keep snapshotSlot: preemption transfers the lease but
            // the entire arbitration chain must restore the user's original slot.
            owner = requestedOwner;
            priority = requestedPriority;
            requestedSlot = slot;
        } else {
            return false;
        }

        expiresAtTick = tick + holdTicks - 1L;
        sendIfChanged(client, requestedSlot);
        return true;
    }

    public boolean acquire(Object requestedOwner, int slot, int requestedPriority) {
        return acquire(requestedOwner, slot, requestedPriority, 1);
    }

    /**
     * Runs one vanilla interaction against a selected hotbar slot. Silent mode
     * projects that slot only through Minecraft's own selected-slot synchronizer;
     * visible mode changes the ordinary inventory selection, synchronizes it via
     * vanilla, keeps it visible for one rendered tick, then safely restores it.
     */
    public boolean runWithSlot(Object requestedOwner, int slot, int requestedPriority,
                               boolean silent, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!silent) {
            return runWithVisibleSlot(requestedOwner, slot, action);
        }
        if (!acquire(requestedOwner, slot, requestedPriority, 1)) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        PlayerInventory inventory = client.player == null ? null : client.player.getInventory();
        int projectionToken = beginLocalProjection(inventory);
        try {
            // PlayerInventorySlotMixin exposes the server-selected stack only to
            // vanilla prediction inside this scope. The visible selected-slot
            // index never changes in silent mode.
            action.run();
        } finally {
            endLocalProjection(inventory, projectionToken);
            releaseAfterAction(requestedOwner);
        }
        return true;
    }

    /** Selects and synchronizes an ordinary visible hotbar slot through vanilla. */
    public boolean selectVisibleSlot(MinecraftClient client, int slot) {
        requireClientThread(client);
        if (slot < 0 || slot > 8 || client.player == null || client.interactionManager == null) {
            return false;
        }
        if (owner != null) {
            return false;
        }
        PlayerInventory inventory = client.player.getInventory();
        if (inventory.getSelectedSlot() == slot) {
            return true;
        }
        inventory.setSelectedSlot(slot);
        syncSelectedSlot(client);
        return true;
    }

    /** Keeps an ordinary action swap visible for one rendered tick, then restores it. */
    public void restoreVisibleNextTick(ClientPlayerEntity expectedPlayer, int originalSlot,
                                       int selectedSlot) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        if (expectedPlayer == null || client.player != expectedPlayer
            || originalSlot < 0 || originalSlot > 8 || selectedSlot < 0 || selectedSlot > 8
            || originalSlot == selectedSlot) {
            return;
        }
        if (visibleActionPlayer == expectedPlayer) {
            if (originalSlot == visibleActionSelectedSlot) {
                // Chain multiple ordinary swaps in one client tick while keeping
                // the user's first visible slot as the eventual restore target.
                visibleActionSelectedSlot = selectedSlot;
                visibleRestoreAtTick = tick + 1L;
                return;
            }
            // The visible slot changed outside the action chain (normally the
            // player scrolled). Never restore over that explicit selection.
            clearVisibleRestore();
        }
        visibleActionPlayer = expectedPlayer;
        visibleActionOriginalSlot = originalSlot;
        visibleActionSelectedSlot = selectedSlot;
        visibleRestoreAtTick = tick + 1L;
    }

    public void release(Object requestedOwner) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        if (!Objects.equals(owner, requestedOwner)) {
            return;
        }
        if (restorePending) {
            // The owner has already emitted an interaction in this tick.
            // Lifecycle cleanup must not collapse its deferred restore back
            // into a same-tick held-item change.
            return;
        }
        // Lifecycle and persistent-lease cancellation happens during START,
        // before vanilla processes physical input. Restore immediately so a
        // later user action in this tick cannot be interpreted with a hidden
        // server-held stack.
        restore(client);
        clearState();
    }

    /** Finishes a scope that has already emitted an attack/use this tick. */
    public void releaseAfterAction(Object requestedOwner) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        if (!Objects.equals(owner, requestedOwner)) {
            return;
        }
        // Do not emit HELD_ITEM_CHANGE after an attack/use in the same tick.
        // beginTick restores before any next-tick planning or input handling.
        restorePending = true;
    }

    public boolean isActive() {
        return owner != null;
    }

    public boolean isOwnedBy(Object requestedOwner) {
        return Objects.equals(owner, requestedOwner);
    }

    public int requestedSlot() {
        return requestedSlot;
    }

    public int snapshotSlot() {
        return snapshotSlot;
    }

    /**
     * Hotbar slot Grim/vanilla treat as the start-of-tick held stack. Silent
     * leases keep the visible spear/sword here while the server slot is the
     * action weapon, so the next attack still inherits that start-stack reach.
     */
    public int startStackSlot() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return -1;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (owner != null && snapshotSlot >= 0 && snapshotSlot <= 8) {
            return snapshotSlot;
        }
        return visible;
    }

    /** True when vanilla has not synchronized a user-visible slot mutation yet. */
    public boolean hasVisibleSelectionChanged(MinecraftClient client) {
        requireClientThread(client);
        if (client.player == null || client.interactionManager == null) {
            return true;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        if (owner != null) {
            return observedVisibleSlot >= 0 && visible != observedVisibleSlot;
        }
        return MinecraftAccess.lastSelectedSlot(client.interactionManager) != visible;
    }

    /**
     * Temporarily exposes the leased stack to vanilla local prediction (for
     * example block-breaking speed) without changing the visible selected slot.
     * Returns a token that must be passed to {@link #endLocalProjection}.
     */
    public int beginLocalProjection(PlayerInventory inventory) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        if (owner == null || inventory == null || client.player == null
            || inventory != client.player.getInventory()
            || requestedSlot < 0 || requestedSlot > 8
            || inventory.getSelectedSlot() == requestedSlot) {
            return -1;
        }
        localProjectionDepth++;
        return requestedSlot;
    }

    public void endLocalProjection(PlayerInventory inventory, int projectionToken) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        if (projectionToken >= 0 && localProjectionDepth > 0) {
            localProjectionDepth--;
        }
    }

    /** Used by PlayerInventorySlotMixin while a vanilla action is being predicted. */
    public int localProjectionSlot(PlayerInventory inventory) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (localProjectionDepth <= 0 || owner == null || inventory == null
            || client.player == null || inventory != client.player.getInventory()) {
            return -1;
        }
        return requestedSlot >= 0 && requestedSlot <= 8 ? requestedSlot : -1;
    }

    /** Used only by vanilla's selected-slot synchronizer mixin. */
    public int serverSlotOr(int visibleSlot) {
        return owner == null ? visibleSlot : requestedSlot;
    }

    public void beginTick(MinecraftClient client) {
        requireClientThread(client);
        tick++;
        if (client.player == null || client.world == null) {
            clearState();
            clearVisibleRestore();
            playerInstance = null;
            return;
        }
        if (playerInstance != client.player) {
            clearState();
            clearVisibleRestore();
            playerInstance = client.player;
        }
        if (restorePending) {
            restore(client);
            clearState();
        }
        if (visibleActionPlayer != null && tick >= visibleRestoreAtTick) {
            restorePendingVisible(client);
        }
        if (owner != null && tick > expiresAtTick) {
            restore(client);
            clearState();
            return;
        }
        observeVisibleSlot(client);
    }

    public void endTick(MinecraftClient client) {
        requireClientThread(client);
        observeVisibleSlot(client);
    }

    /** Clears leases without writing to a connection that may already be closed. */
    public void clear() {
        clearState();
        clearVisibleRestore();
        playerInstance = null;
    }

    private void observeVisibleSlot(MinecraftClient client) {
        if (owner == null || client.player == null) {
            return;
        }

        int visible = client.player.getInventory().getSelectedSlot();
        if (visible != observedVisibleSlot) {
            observedVisibleSlot = visible;
            snapshotSlot = visible;
            // Vanilla may just have synchronized the user's newly visible slot.
            assumedServerSlot = visible;
            if (restorePending) {
                // A user slot change after the action is authoritative. Do not
                // reassert the hidden action stack after its interaction; end
                // the lease and let vanilla's visible selection stand.
                clearState();
                syncSelectedSlot(client);
                return;
            }
            sendIfChanged(client, requestedSlot);
        }
    }

    private void restore(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        int visible = client.player.getInventory().getSelectedSlot();
        int restoreSlot = visible >= 0 && visible <= 8 ? visible : snapshotSlot;
        if (restoreSlot >= 0 && restoreSlot <= 8) {
            sendIfChanged(client, restoreSlot);
        }
    }

    private void sendIfChanged(MinecraftClient client, int slot) {
        if (slot == assumedServerSlot || client.player == null) {
            return;
        }
        if (client.interactionManager != null) {
            // Project the requested server slot only while invoking Minecraft's
            // own synchronizer. Vanilla constructs and sends its ordinary slot
            // update and advances its private lastSelectedSlot cache itself.
            int previousRequest = requestedSlot;
            requestedSlot = slot;
            try {
                MinecraftAccess.syncSelectedSlot(client.interactionManager);
            } finally {
                requestedSlot = previousRequest;
            }
        }
        assumedServerSlot = slot;
    }

    private boolean runWithVisibleSlot(Object requestedOwner, int slot, Runnable action) {
        MinecraftClient client = MinecraftClient.getInstance();
        requireClientThread(client);
        Objects.requireNonNull(requestedOwner, "requestedOwner");
        if (slot < 0 || slot > 8 || client.player == null || client.world == null
            || client.interactionManager == null || owner != null) {
            return false;
        }

        PlayerInventory inventory = client.player.getInventory();
        int visibleSlot = inventory.getSelectedSlot();
        if (visibleSlot == slot) {
            action.run();
            return true;
        }

        inventory.setSelectedSlot(slot);
        syncSelectedSlot(client);
        try {
            action.run();
        } finally {
            restoreVisibleNextTick(client.player, visibleSlot, slot);
        }
        return true;
    }

    private static void syncSelectedSlot(MinecraftClient client) {
        if (client.interactionManager != null) {
            MinecraftAccess.syncSelectedSlot(client.interactionManager);
        }
    }

    private void restorePendingVisible(MinecraftClient client) {
        if (client.player == visibleActionPlayer
            && client.player.getInventory().getSelectedSlot() == visibleActionSelectedSlot) {
            client.player.getInventory().setSelectedSlot(visibleActionOriginalSlot);
            syncSelectedSlot(client);
        }
        clearVisibleRestore();
    }

    private void clearVisibleRestore() {
        visibleActionPlayer = null;
        visibleActionOriginalSlot = -1;
        visibleActionSelectedSlot = -1;
        visibleRestoreAtTick = 0L;
    }

    private void clearState() {
        owner = null;
        priority = 0;
        requestedSlot = -1;
        snapshotSlot = -1;
        observedVisibleSlot = -1;
        assumedServerSlot = -1;
        expiresAtTick = 0L;
        restorePending = false;
        localProjectionDepth = 0;
    }

    private static void requireClientThread(MinecraftClient client) {
        if (!client.isOnThread()) {
            throw new IllegalStateException("Silent slot operations must run on the Minecraft client thread");
        }
    }
}
