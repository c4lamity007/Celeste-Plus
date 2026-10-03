package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.MathHelper;

/**
 * Refills offhand first, then the reserve. The offhand leg uses vanilla's
 * atomic offhand-swap action, while the reserve remains a normal staged
 * left-click transfer. This keeps the cursor clear for an immediate pop
 * refill and never abandons an item on it.
 */
public final class SilentTotemFeature implements ModuleFeature {
    private enum Phase { IDLE, PICK_SOURCE, PLACE_DESTINATION, RETURN_DISPLACED }

    private boolean stateInitialized;
    private long observedPops;
    private boolean popRefillActive;
    private boolean useTwoTickPopDelay;
    private int refillNotBeforeAge;
    private SilentInventoryScreen ownedScreen;
    private Phase phase = Phase.IDLE;
    private int sourceInventoryIndex = -1;
    private int destinationInventoryIndex = -1;
    private int closeAge;
    /** First START tick after the fake screen has emitted neutral vanilla input. */
    private int neutralInputReadyAtAge;

    @Override
    public String name() {
        return "Silent Totem";
    }

    @Override
    public void onEnable(ModuleContext context) {
        reset(context.client(), false);
        updatePopState(context);
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null || client.world == null || client.interactionManager == null
            || !client.player.isAlive() || client.player.isSpectator()) {
            reset(client, true);
            return;
        }
        ClientPlayerEntity player = client.player;

        updatePopState(context);
        reconcileOwnedScreen(client);
        var visibleTotem = dev.c4lam.celeste.module.ModuleManager.getInstance().module("Legit Auto Totem");
        if (visibleTotem != null && visibleTotem.enabled()) {
            // Visible refilling owns the inventory while selected. Finish only
            // cleanup of our exact silent screen; never touch a user's screen.
            if (ownedScreen != null && client.currentScreen == ownedScreen) {
                pauseForInvulnerability(client, player);
            }
            return;
        }
        closeFinishedScreen(context);
        if (!player.canTakeDamage()) {
            pauseForInvulnerability(client, player);
            return;
        }
        if (!refillEnabled(context)) {
            return;
        }

        // Open our blank inventory immediately after a pop, but do not click
        // until a full neutral-input tick has elapsed. The first inventory
        // pickup then lands one or two client ticks after the pop, and the
        // destination click is necessarily in its own later tick.
        if (phase == Phase.IDLE && player.age < refillNotBeforeAge) {
            ensureInventoryContext(client, player);
            return;
        }

        if (phase == Phase.IDLE) {
            beginNextTransfer(context);
        }
        continueTransfer(context);
    }

    private void beginNextTransfer(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        Transfer transfer = findNextTransfer(player, configuredHotbarSlot(context));
        if (transfer == null) {
            finishPopRefill(context);
            return;
        }
        sourceInventoryIndex = transfer.sourceInventoryIndex();
        destinationInventoryIndex = transfer.destinationInventoryIndex();
        phase = Phase.PICK_SOURCE;
    }

    private void continueTransfer(ModuleContext context) {
        MinecraftClient client = context.client();
        ClientPlayerEntity player = client.player;
        if (!context.module().enabled() || phase == Phase.IDLE || player == null
            || !ensureInventoryContext(client, player)) {
            return;
        }
        if (!neutralInventoryInputReady(player)) {
            return;
        }

        switch (phase) {
            case PICK_SOURCE -> {
                if (destinationInventoryIndex == PlayerInventory.OFF_HAND_SLOT) {
                    swapSourceIntoOffhand(client, player);
                } else {
                    pickSource(client, player);
                }
            }
            case PLACE_DESTINATION -> placeDestination(client, player, configuredHotbarSlot(context));
            case RETURN_DISPLACED -> returnDisplaced(client, player);
            case IDLE -> { }
        }
    }

    /**
     * Opens only our blank player inventory. A real InventoryScreen remains
     * wholly user-owned: no automated click is permitted until it is closed
     * and this exact screen instance has been opened instead.
     */
    private boolean ensureInventoryContext(MinecraftClient client, ClientPlayerEntity player) {
        if (client.currentScreen == null) {
            SilentInventoryScreen screen = new SilentInventoryScreen(player);
            client.setScreen(screen);
            ownedScreen = screen;
            // START_CLIENT_TICK runs before ClientPlayerEntity emits this
            // tick's PlayerInput packet. Always let the fake inventory send
            // one neutral vanilla input/sprint state before its first click.
            neutralInputReadyAtAge = player.age + 1;
        }
        return client.currentScreen == ownedScreen
            && client.currentScreen instanceof SilentInventoryScreen
            && player.currentScreenHandler == player.playerScreenHandler;
    }

    /**
     * Grim's inventory-click check reads the last PlayerInput state the server
     * has received. The blank screen's input mixin sends DEFAULT during the
     * preceding client tick; this gate guarantees the click follows it rather
     * than being ordered before it. It adds no delay between later transfer
     * clicks while the same screen remains open.
     */
    private boolean neutralInventoryInputReady(ClientPlayerEntity player) {
        if (player.age < neutralInputReadyAtAge) {
            return false;
        }
        var input = player.input.playerInput;
        if (player.isSprinting() || input.forward() || input.backward() || input.left() || input.right()
            || input.jump() || input.sneak() || input.sprint()) {
            // An unexpected input replacement must receive its own complete
            // neutral packet before any inventory packet is allowed.
            neutralInputReadyAtAge = player.age + 1;
            return false;
        }
        return true;
    }

    /** Left-click 1 of a normal transfer: pick the totem up from its live source. */
    private void pickSource(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;
        if (!handler.getCursorStack().isEmpty()) {
            abortTransfer();
            return;
        }
        Slot source = playerSlot(handler, player.getInventory(), sourceInventoryIndex);
        if (source == null || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            abortTransfer();
            return;
        }
        client.interactionManager.clickSlot(handler.syncId, source.id, 0, SlotActionType.PICKUP, player);
        phase = Phase.PLACE_DESTINATION;
    }

    /**
     * Vanilla's own offhand-swap click exchanges the live source with the
     * offhand atomically. Unlike a cursor pickup followed by an offhand click,
     * no carried totem exists between actions and the player handler remains
     * coherent even when the former offhand stack is non-empty.
     */
    private void swapSourceIntoOffhand(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;
        if (!handler.getCursorStack().isEmpty() || hasOffhandTotem(player)) {
            abortTransfer();
            return;
        }
        Slot source = playerSlot(handler, player.getInventory(), sourceInventoryIndex);
        if (source == null || !source.getStack().isOf(Items.TOTEM_OF_UNDYING)) {
            abortTransfer();
            return;
        }
        client.interactionManager.clickSlot(handler.syncId, source.id, 40, SlotActionType.SWAP, player);
        phase = Phase.IDLE;
        closeAge = player.age + 1;
    }

    /** Left-click 2: place the totem into offhand or the chosen reserve. */
    private void placeDestination(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
        ScreenHandler handler = player.playerScreenHandler;
        if (!handler.getCursorStack().isOf(Items.TOTEM_OF_UNDYING)
            || !destinationStillNeeded(player, destinationInventoryIndex, hotbarSlot)) {
            abortTransfer();
            return;
        }
        Slot destination = playerSlot(handler, player.getInventory(), destinationInventoryIndex);
        if (destination == null) {
            abortTransfer();
            return;
        }

        client.interactionManager.clickSlot(handler.syncId, destination.id, 0, SlotActionType.PICKUP, player);
        // When the destination was empty this is the second and final click.
        // If it held an item, vanilla leaves that displaced item on the cursor;
        // one more left-click returns it to the now-empty source slot.
        phase = handler.getCursorStack().isEmpty() ? Phase.IDLE : Phase.RETURN_DISPLACED;
        if (phase == Phase.IDLE) {
            // Let the ordinary placement click finish its tick, then close on
            // the next START tick if offhand/reserve no longer need work.
            closeAge = player.age + 1;
        }
    }

    private void returnDisplaced(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;
        if (handler.getCursorStack().isEmpty()) {
            phase = Phase.IDLE;
            closeAge = player.age + 1;
            return;
        }
        Slot source = playerSlot(handler, player.getInventory(), sourceInventoryIndex);
        if (source == null || !source.getStack().isEmpty()) {
            // Never close our screen while vanilla still has an item on the
            // cursor. The session stays live until an ordinary return click is
            // again safe.
            return;
        }
        client.interactionManager.clickSlot(handler.syncId, source.id, 0, SlotActionType.PICKUP, player);
        if (handler.getCursorStack().isEmpty()) {
            phase = Phase.IDLE;
            closeAge = player.age + 2;
        }
    }

    private void closeFinishedScreen(ModuleContext context) {
        MinecraftClient client = context.client();
        if (ownedScreen == null || client.currentScreen != ownedScreen || client.player == null
            || phase != Phase.IDLE || !client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            return;
        }
        boolean hasMore = refillEnabled(context)
            && findNextTransfer(client.player, configuredHotbarSlot(context)) != null;
        if ((!context.module().enabled() || !hasMore) && client.player.age >= closeAge) {
            closeOwnedScreen(client, false);
        }
    }

    /**
     * Do not continue an automated transfer while the player is damage-immune.
     * A staged left-click can leave an item on vanilla's cursor, so first put
     * it back into the now-empty source slot, then close our exact fake screen.
     * The next eligible tick plans a fresh, live transfer instead of resuming
     * an old slot location.
     */
    private void pauseForInvulnerability(MinecraftClient client, ClientPlayerEntity player) {
        ScreenHandler handler = player.playerScreenHandler;
        if (!handler.getCursorStack().isEmpty()) {
            Slot source = playerSlot(handler, player.getInventory(), sourceInventoryIndex);
            if (source == null || !source.getStack().isEmpty() || client.interactionManager == null) {
                // Closing with an unknown cursor would risk dropping or
                // desynchronizing it. Keep this one safe vanilla state until
                // it can be reconciled instead.
                return;
            }
            client.interactionManager.clickSlot(handler.syncId, source.id, 0, SlotActionType.PICKUP, player);
            if (!handler.getCursorStack().isEmpty()) {
                return;
            }
        }
        abortTransfer();
        closeOwnedScreen(client, false);
    }

    private void reconcileOwnedScreen(MinecraftClient client) {
        if (ownedScreen != null && client.currentScreen != ownedScreen) {
            // The source/destination indices exist only for the exact open
            // inventory context. Closing or replacing it discards the plan;
            // no click location survives into a later inventory screen.
            ownedScreen = null;
            neutralInputReadyAtAge = 0;
            abortTransfer();
        }
    }

    private void updatePopState(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        if (player == null) {
            return;
        }
        long pops = dev.c4lam.celeste.compat.TotemPopTracker.counter(context.client());
        if (!stateInitialized) {
            stateInitialized = true;
            observedPops = pops;
        }
        boolean popped = pops > observedPops;
        if (popped) {
            // Keep actual replenish intervals varied while staying within the
            // requested one/two tick window. Alternating makes every five-pop
            // sample contain both delays rather than occasionally producing a
            // run of identical timings.
            int delayTicks = useTwoTickPopDelay ? 2 : 1;
            useTwoTickPopDelay = !useTwoTickPopDelay;
            refillNotBeforeAge = player.age + delayTicks;
        }
        if (context.toggle("Only after pop", false)) {
            if (popped) {
                popRefillActive = true;
            }
        } else {
            popRefillActive = false;
        }
        observedPops = pops;
    }

    private boolean refillEnabled(ModuleContext context) {
        return !context.toggle("Only after pop", false) || popRefillActive;
    }

    private void finishPopRefill(ModuleContext context) {
        if (context.toggle("Only after pop", false)) {
            popRefillActive = false;
        }
        if (context.client().player != null) {
            closeAge = context.client().player.age + 1;
        }
    }

    private static Transfer findNextTransfer(ClientPlayerEntity player, int hotbarSlot) {
        int destination = nextDestination(player, hotbarSlot);
        if (destination < 0) {
            return null;
        }
        int source = findTotemSource(player, hotbarSlot, destination, true);
        if (source < 0) {
            source = findTotemSource(player, hotbarSlot, destination, false);
        }
        return source < 0 ? null : new Transfer(source, destination);
    }

    /** Priv policy: offhand first, then a single selected reserve hotbar slot. */
    private static int nextDestination(ClientPlayerEntity player, int hotbarSlot) {
        if (!hasOffhandTotem(player)) {
            return PlayerInventory.OFF_HAND_SLOT;
        }
        ItemStack reserve = player.getInventory().getStack(hotbarSlot);
        if (reserve.isOf(Items.PAPER) || reserve.isOf(Items.TOTEM_OF_UNDYING)) {
            return -1;
        }
        return hotbarSlot;
    }

    private static boolean destinationStillNeeded(ClientPlayerEntity player, int destination, int hotbarSlot) {
        if (destination == PlayerInventory.OFF_HAND_SLOT) {
            return !hasOffhandTotem(player);
        }
        if (destination != hotbarSlot || destination < 0 || destination > 8) {
            return false;
        }
        ItemStack reserve = player.getInventory().getStack(destination);
        return !reserve.isOf(Items.PAPER) && !reserve.isOf(Items.TOTEM_OF_UNDYING);
    }

    private static int findTotemSource(ClientPlayerEntity player, int protectedHotbarSlot,
                                       int destination, boolean protectReserve) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (protectReserve && destination == PlayerInventory.OFF_HAND_SLOT && slot == protectedHotbarSlot) {
                continue;
            }
            if (inventory.getStack(slot).isOf(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private static Slot playerSlot(ScreenHandler handler, PlayerInventory inventory, int inventoryIndex) {
        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory && slot.getIndex() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    private static int configuredHotbarSlot(ModuleContext context) {
        return MathHelper.clamp((int) Math.round(context.slider("Hotbar slot", 3.0D)) - 1, 0, 8);
    }

    private static boolean hasOffhandTotem(ClientPlayerEntity player) {
        return player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    @Override
    public void onDisable(ModuleContext context) {
        reset(context.client(), false);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        reset(context.client(), true);
    }

    private void reset(MinecraftClient client, boolean disconnected) {
        if (!disconnected && ownedScreen != null && client.currentScreen == ownedScreen
            && client.player != null && !client.player.playerScreenHandler.getCursorStack().isEmpty()) {
            // A source click always leaves its original slot empty, so this
            // immediate ordinary left-click can safely return the carried stack
            // before disabling the module closes its invisible context.
            Slot source = playerSlot(client.player.playerScreenHandler, client.player.getInventory(), sourceInventoryIndex);
            if (source != null && source.getStack().isEmpty() && client.interactionManager != null) {
                client.interactionManager.clickSlot(client.player.playerScreenHandler.syncId, source.id, 0,
                    SlotActionType.PICKUP, client.player);
            }
        }
        // Death/world replacement must never leave the blank transport screen
        // active. In that case inventory state is being replaced by vanilla,
        // so closing the client-only screen is safer than emitting one more
        // inventory click against an invalid player handler.
        if (ownedScreen != null && client.currentScreen == ownedScreen
            && (disconnected || (client.player != null && client.player.playerScreenHandler.getCursorStack().isEmpty()))) {
            closeOwnedScreen(client, disconnected);
        }
        stateInitialized = false;
        observedPops = 0;
        popRefillActive = false;
        useTwoTickPopDelay = false;
        refillNotBeforeAge = 0;
        neutralInputReadyAtAge = 0;
        ownedScreen = null;
        abortTransfer();
        closeAge = 0;
    }

    /**
     * A normal completion must close the same way the vanilla inventory does,
     * rather than merely replacing the client screen. That produces the
     * ordinary player-inventory close lifecycle after the final click. Death
     * and disconnect deliberately avoid one more inventory packet.
     */
    private void closeOwnedScreen(MinecraftClient client, boolean disconnected) {
        SilentInventoryScreen screen = ownedScreen;
        ownedScreen = null;
        neutralInputReadyAtAge = 0;
        if (screen == null || client.currentScreen != screen) {
            return;
        }
        if (disconnected) {
            client.setScreen(null);
        } else {
            screen.close();
        }
    }

    private void abortTransfer() {
        phase = Phase.IDLE;
        sourceInventoryIndex = -1;
        destinationInventoryIndex = -1;
    }

    private record Transfer(int sourceInventoryIndex, int destinationInventoryIndex) {
    }
}
