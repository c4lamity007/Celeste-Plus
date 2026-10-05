package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.compat.TotemPopTracker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import java.util.ArrayList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import org.lwjgl.glfw.GLFW;

/** Visible vanilla inventory input. No hidden screen, packet synthesis or cursor overlay. */
public final class LegitAutoTotemFeature implements ModuleFeature {
    private static final int PRIORITY = 230;
    private static final long MANUAL_PAUSE_NANOS = 300_000_000L;
    private static final java.util.Map<Integer, Integer> SCANCODE_KEYS = new java.util.HashMap<>();
    private static final int[][] KEY_RANGES = {{32, 32}, {39, 39}, {44, 57}, {59, 59}, {61, 61},
        {65, 93}, {96, 96}, {161, 162}, {256, 269}, {280, 284}, {290, 314}, {320, 336}, {340, 348}};
    private final LegitTotemPolicy policy = new LegitTotemPolicy();
    private final TotemCursorMotion motion = new TotemCursorMotion();
    private final TotemOpeningInput openingInput = new TotemOpeningInput();
    private final double[] cursorX = new double[1], cursorY = new double[1];
    private InventoryScreen screen;
    private Object sessionPlayer;
    private TotemCursorMotion.Point centerOrigin, lastWarp, lastObserved;
    private TotemCursorMotion.Box targetBox;
    private int sourceId = -1, destination = -1;
    private int openedAge, lastSwapAge, width, height;
    private long pauseUntil, lastFrame;
    private boolean reacted, transferred;

    @Override public String name() { return "Legit Auto Totem"; }

    @Override public void onEnable(ModuleContext context) {
        resetSession();
        policy.reset(TotemPopTracker.counter(context.client()), context.toggle("Auto open", true));
    }

    @Override public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        boolean autoOpen = context.toggle("Auto open", true);
        long pops = TotemPopTracker.counter(client);
        if (!eligible(context)) {
            resetSession();
            policy.reset(pops, autoOpen);
            return;
        }
        policy.observe(pops, autoOpen);
        if (screen != null && (client.currentScreen != screen || client.player != sessionPlayer)) {
            // Esc, a container, death/respawn or a replaced screen cancels this
            // session, including a pending auto-open. Never reopen after Esc.
            resetSession();
            policy.openedOrCancelled();
        }
        if (!client.isWindowFocused() || client.getOverlay() != null) {
            openingInput.reset();
            invalidateMotion();
            return;
        }
        if (client.currentScreen == null && autoOpen
            && policy.wantsOpen((int) Math.round(context.slider("Open after pops", 1)))
            && LegitAutoTotemInventorySupport.playerInventoryAvailable(client)
            && nextDestination(context) >= 0 && hasMainInventoryTotem(client)) {
            // The same visible survival inventory as E, including native input
            // blocking, normal cursor unlock, recipe book and close lifecycle.
            client.setScreen(new InventoryScreen(client.player));
            policy.openedOrCancelled();
        }
        if (!LegitAutoTotemInventorySupport.visibleInventoryAvailable(client)) {
            invalidateMotion();
            return;
        }
        if (screen != client.currentScreen) {
            resetSession();
            screen = (InventoryScreen) client.currentScreen;
            sessionPlayer = client.player;
            openedAge = client.player.age;
            width = screen.width; height = screen.height;
            centerOrigin = new TotemCursorMotion.Point(width * .5, height * .5);
            InputUtil.Key inventoryKey = MinecraftAccess.boundKey(client.options.inventoryKey);
            openingInput.opened(inventoryKey, inputDown(client, inventoryKey));
            policy.openedOrCancelled();
        }
        int next = nextDestination(context);
        if (next < 0) {
            invalidateTarget();
            if (LegitTotemPolicy.mayClose(context.toggle("Auto close", true), transferred,
                    client.player.playerScreenHandler.getCursorStack().isEmpty(), hasOffhand(client),
                    hasReserve(context), client.player.age, lastSwapAge)
                && System.nanoTime() >= pauseUntil && !physicalActionDown(client)) {
                screen.close();
                resetSession();
            }
            return;
        }
        Slot target = source(client);
        if (destination != next || !validSource(client, target)) {
            invalidateTarget();
            destination = next;
            selectSource(client);
        }
    }

    @Override public void onRenderFrame(ModuleContext context) {
        MinecraftClient client = context.client();
        if (!sessionReady(context)) {
            openingInput.reset();
            invalidateMotion();
            return;
        }
        if (nextDestination(context) != destination) {
            invalidateMotion();
            return;
        }
        Slot selected = source(client);
        if (!validSource(client, selected)) { invalidateTarget(); return; }
        if (width != screen.width || height != screen.height) {
            openingInput.reset();
            width = screen.width; height = screen.height;
            centerOrigin = new TotemCursorMotion.Point(width * .5, height * .5);
            invalidateTarget();
            return;
        }
        LegitAutoTotemInventorySupport.SlotBounds bounds = LegitAutoTotemInventorySupport.slotBounds(client, selected);
        if (bounds == null) { invalidateMotion(); return; }
        // Keep the actual pointer away from the neighboring slot's hit margin.
        TotemCursorMotion.Box box = new TotemCursorMotion.Box(bounds.x() + 2, bounds.y() + 2,
            bounds.width() - 4, bounds.height() - 4);
        long now = System.nanoTime();
        TotemCursorMotion.Point actual = cursor(client);
        if (actual == null) { invalidateMotion(); return; }
        boolean manualMove = lastWarp != null && distance(actual, lastWarp) > 8;
        InputUtil.Key inventoryKey = MinecraftAccess.boundKey(client.options.inventoryKey);
        // Holding the press that opened this inventory is not a new manual
        // action. Do not turn it into a renewable 300 ms startup pause. Actual
        // swaps/closing still require every physical action to be released.
        boolean manualAction = openingInput.blocksMovement(inventoryKey, inputDown(client, inventoryKey),
            physicalActionDown(client, false));
        if (manualAction || manualMove
            || now < pauseUntil && lastObserved != null && distance(actual, lastObserved) > 1) {
            pauseUntil = now + MANUAL_PAUSE_NANOS;
            invalidateMotion();
        }
        lastObserved = actual;
        if (now < pauseUntil) return;
        // Restart timing after a stalled render loop; never complete an unseen
        // reaction just because the window stopped drawing for a long time.
        if (lastFrame != 0 && now - lastFrame > 250_000_000L) motion.reset();
        lastFrame = now;
        var step = motion.step(actual, box, now, context.slider("Speed", 650),
            Math.round(context.slider("Reaction", 80)));
        targetBox = box;
        reacted = step.reactionReady();
        var next = step.position();
        if (!Double.isFinite(next.x()) || !Double.isFinite(next.y())
            || next.x() < 0 || next.y() < 0 || next.x() >= screen.width || next.y() >= screen.height) {
            invalidateMotion();
            return;
        }
        // GLFW moves the real OS cursor only inside this focused game window.
        // Minecraft's normal cursor callback and next GUI render update hover.
        var window = client.getWindow();
        GLFW.glfwSetCursorPos(window.getHandle(), next.x() * window.getWidth() / window.getScaledWidth(),
            next.y() * window.getHeight() / window.getScaledHeight());
        lastWarp = next;
    }

    @Override public void onActionTick(ModuleContext context) {
        if (!reacted || !sessionReady(context) || sourceId < 0) return;
        MinecraftClient client = context.client();
        if (client.player.age <= openedAge || client.player.age <= lastSwapAge) return;
        InventoryScreen plannedScreen = screen;
        int plannedSource = sourceId, plannedDestination = destination;
        ActionScheduler.getInstance().submit(this, PRIORITY, current -> {
            if (!reacted || !sessionReady(context) || screen != plannedScreen
                || sourceId != plannedSource || destination != plannedDestination
                || nextDestination(context) != plannedDestination
                || System.nanoTime() < pauseUntil || System.nanoTime() - lastFrame > 250_000_000L
                || physicalActionDown(current)) return;
            Slot selected = source(current);
            if (!validSource(current, selected)) { invalidateTarget(); return; }
            TotemCursorMotion.Point actual = cursor(current);
            var liveBounds = LegitAutoTotemInventorySupport.slotBounds(current, selected);
            if (actual == null || liveBounds == null || targetBox == null
                || !targetBox.contains(actual) || !liveBounds.contains(actual.x(), actual.y())
                || LegitAutoTotemInventorySupport.focusedSlot(current) != selected
                || MinecraftAccess.focusedSlot(screen) != selected) return;
            // A key event goes through vanilla's actual focused-slot handling.
            // Do not set keybinding held/queued state or manufacture click packets.
            KeyBinding binding = plannedDestination == 40 ? current.options.swapHandsKey
                : current.options.hotbarKeys[plannedDestination];
            boolean dispatched = pressSwapBinding(current, screen, binding, plannedDestination, actual);
            boolean filled = plannedDestination == 40 ? hasOffhand(current) : hasReserve(context);
            if (dispatched && filled && current.currentScreen == screen) {
                lastSwapAge = current.player.age;
                transferred = true;
                invalidateTarget();
            } else {
                // Unbound/conflicting key or a screen consuming it: never spam
                // fallback clicks. Retry only after a fresh visible reaction.
                pauseUntil = System.nanoTime() + MANUAL_PAUSE_NANOS;
                invalidateMotion();
            }
        });
    }

    private void selectSource(MinecraftClient client) {
        var candidates = new ArrayList<TotemCursorMotion.Candidate>();
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (!validSource(client, slot)) continue;
            var bounds = LegitAutoTotemInventorySupport.slotBounds(client, slot);
            if (bounds != null) candidates.add(new TotemCursorMotion.Candidate(slot.getIndex(),
                new TotemCursorMotion.Box(bounds.x(), bounds.y(), bounds.width(), bounds.height())));
        }
        var ordered = TotemCursorMotion.centerOutMainSlots(candidates, centerOrigin);
        if (ordered.isEmpty()) return;
        int inventoryIndex = ordered.getFirst().inventoryIndex();
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory() && slot.getIndex() == inventoryIndex) {
                sourceId = slot.id;
                return;
            }
        }
    }

    private boolean sessionReady(ModuleContext context) {
        MinecraftClient client = context.client();
        return eligible(context) && screen != null && client.currentScreen == screen
            && client.player == sessionPlayer && client.isWindowFocused() && client.getOverlay() == null
            && client.getWindow() != null && !client.mouse.isCursorLocked()
            && LegitAutoTotemInventorySupport.visibleInventoryAvailable(client);
    }

    private static boolean eligible(ModuleContext context) {
        MinecraftClient client = context.client();
        return context.module().enabled() && client.player != null && client.world != null
            && client.interactionManager != null && client.player.isAlive()
            && !client.player.isSpectator() && !client.player.isInCreativeMode();
    }

    private Slot source(MinecraftClient client) {
        var slots = client.player.playerScreenHandler.slots;
        return sourceId >= 0 && sourceId < slots.size() ? slots.get(sourceId) : null;
    }

    private static boolean validSource(MinecraftClient client, Slot slot) {
        return slot != null && slot.inventory == client.player.getInventory() && slot.getIndex() >= 9
            && slot.getIndex() <= 35 && slot.isEnabled() && slot.canTakeItems(client.player)
            && slot.getStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private static boolean hasMainInventoryTotem(MinecraftClient client) {
        for (Slot slot : client.player.playerScreenHandler.slots) if (validSource(client, slot)) return true;
        return false;
    }

    private static boolean hasOffhand(MinecraftClient client) {
        return client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
    }

    private static boolean hasReserve(ModuleContext context) {
        return context.client().player.getInventory().getStack(hotbarSlot(context)).isOf(Items.TOTEM_OF_UNDYING);
    }

    private static int hotbarSlot(ModuleContext context) {
        return Math.clamp((int) Math.round(context.slider("Hotbar slot", 3)) - 1, 0, 8);
    }

    private static int nextDestination(ModuleContext context) {
        return LegitTotemPolicy.nextDestination(hasOffhand(context.client()), hasReserve(context), hotbarSlot(context));
    }

    private TotemCursorMotion.Point cursor(MinecraftClient client) {
        var window = client.getWindow();
        if (window == null || window.getWidth() <= 0 || window.getHeight() <= 0) return null;
        GLFW.glfwGetCursorPos(window.getHandle(), cursorX, cursorY);
        double x = cursorX[0] * window.getScaledWidth() / window.getWidth();
        double y = cursorY[0] * window.getScaledHeight() / window.getHeight();
        return Double.isFinite(x) && Double.isFinite(y) ? new TotemCursorMotion.Point(x, y) : null;
    }

    private static double distance(TotemCursorMotion.Point a, TotemCursorMotion.Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static boolean physicalActionDown(MinecraftClient client) {
        return physicalActionDown(client, true);
    }

    private static boolean physicalActionDown(MinecraftClient client, boolean includeInventory) {
        long handle = client.getWindow().getHandle();
        InputUtil.Key inventoryKey = MinecraftAccess.boundKey(client.options.inventoryKey);
        for (int button = 0; button <= GLFW.GLFW_MOUSE_BUTTON_LAST; button++) {
            if (!includeInventory && inventoryKey.getCategory() == InputUtil.Type.MOUSE
                && inventoryKey.getCode() == button) continue;
            if (GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS) return true;
        }
        if ((includeInventory && inputDown(client, inventoryKey)) || keyDown(client, client.options.swapHandsKey)
            || keyDown(client, client.options.dropKey)) return true;
        for (KeyBinding binding : client.options.hotbarKeys) if (keyDown(client, binding)) return true;
        return false;
    }

    private static boolean keyDown(MinecraftClient client, KeyBinding binding) {
        return inputDown(client, MinecraftAccess.boundKey(binding));
    }

    private static boolean inputDown(MinecraftClient client, InputUtil.Key key) {
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            int button = key.getCode();
            return button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                && GLFW.glfwGetMouseButton(client.getWindow().getHandle(), button) == GLFW.GLFW_PRESS;
        }
        int code = keyboardCode(key);
        return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
            && InputUtil.isKeyPressed(client.getWindow(), code);
    }

    private static boolean pressSwapBinding(MinecraftClient client, InventoryScreen screen, KeyBinding binding,
                                             int destination, TotemCursorMotion.Point cursor) {
        InputUtil.Key key = MinecraftAccess.boundKey(binding);
        if (key.getCode() < 0) return false;
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            // Primary buttons pick items up, not hotbar-swap; do not reinterpret
            // them. Extra mouse bindings follow the normal screen click path.
            if (key.getCode() < 2 || key.getCode() > GLFW.GLFW_MOUSE_BUTTON_LAST) return false;
            Click click = new Click(cursor.x(), cursor.y(), new MouseInput(key.getCode(), 0));
            if (destination != 40 && client.options.swapHandsKey.matchesMouse(click)) return false;
            for (int i = 0; i < client.options.hotbarKeys.length; i++) {
                if (i != destination && client.options.hotbarKeys[i].matchesMouse(click)) return false;
            }
            screen.mouseClicked(click, false);
            if (client.currentScreen == screen) screen.mouseReleased(click);
            return true;
        }
        int code = keyboardCode(key);
        if (code < GLFW.GLFW_KEY_SPACE || code > GLFW.GLFW_KEY_LAST) return false;
        KeyInput input = new KeyInput(code, key.getCategory() == InputUtil.Type.SCANCODE
            ? key.getCode() : GLFW.glfwGetKeyScancode(code), 0);
        if (navigationKey(input.key()) || client.options.inventoryKey.matchesKey(input) || client.options.dropKey.matchesKey(input)
            || client.options.chatKey.matchesKey(input)
            || client.options.pickItemKey.matchesKey(input)
            || destination != 40 && client.options.swapHandsKey.matchesKey(input)) return false;
        for (int i = 0; i < client.options.hotbarKeys.length; i++) {
            if (i != destination && client.options.hotbarKeys[i].matchesKey(input)) return false;
        }
        screen.keyPressed(input);
        if (client.currentScreen == screen) screen.keyReleased(input);
        return true;
    }

    private static boolean navigationKey(int key) {
        return key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
            || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_TAB
            || key >= GLFW.GLFW_KEY_RIGHT && key <= GLFW.GLFW_KEY_END;
    }

    private static int keyboardCode(InputUtil.Key key) {
        if (key.getCategory() == InputUtil.Type.KEYSYM) return key.getCode();
        if (key.getCategory() != InputUtil.Type.SCANCODE || key.getCode() < 0) return GLFW.GLFW_KEY_UNKNOWN;
        // Uncommon raw-scancode binds still need physical-key arbitration and
        // navigation-key rejection. Resolve once, not by polling every key/frame.
        return SCANCODE_KEYS.computeIfAbsent(key.getCode(), scan -> {
            for (int[] range : KEY_RANGES) for (int code = range[0]; code <= range[1]; code++) {
                if (GLFW.glfwGetKeyScancode(code) == scan) return code;
            }
            return GLFW.GLFW_KEY_UNKNOWN;
        });
    }

    private void invalidateMotion() {
        motion.reset(); reacted = false; targetBox = null; lastFrame = 0; lastWarp = null;
    }

    private void invalidateTarget() {
        invalidateMotion(); sourceId = -1; destination = -1;
    }

    private void resetSession() {
        openingInput.reset();
        invalidateTarget(); screen = null; sessionPlayer = null; centerOrigin = null;
        lastObserved = null; pauseUntil = 0; transferred = false; openedAge = lastSwapAge = -1;
    }

    @Override public void onDisable(ModuleContext context) { resetSession(); policy.openedOrCancelled(); }
    @Override public void onWorldChanged(ModuleContext context) {
        resetSession(); policy.reset(TotemPopTracker.counter(context.client()), context.toggle("Auto open", true));
    }
}
