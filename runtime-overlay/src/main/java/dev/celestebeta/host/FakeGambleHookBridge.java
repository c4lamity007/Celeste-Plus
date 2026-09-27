package dev.celestebeta.host;

import java.util.Objects;

/** Canonical JDK-only callbacks for the existing client-local visual inventory simulation. */
public final class FakeGambleHookBridge {
    private static final Object LOCK = new Object();
    private static Registration current;
    public interface Handler {
        boolean click(int syncId, int slotId, int button, Object actionType, Object player);
        boolean worldEvent(Object handler, int eventId, Object pos, int data);
        boolean pickup(Object item, Object player);
        void itemTick(Object item);
        void afterSync();
        void screenClosed(Object handler, Object player);
        void noteDispenser(Object hit);
        boolean pick(Object client);
        void screenRender(Object screen);
        default Object interactBlock(Object player, Object hand, Object hit) { noteDispenser(hit); return null; }
        default Object interactItem(Object player, Object hand) { return null; }
        default Object interactEntity(Object player, Object hand) { return null; }
        default Boolean attackBlock(Object pos, Object direction) { return null; }
        default Boolean updateBlockBreakingProgress(Object pos, Object direction) { return null; }
        default Boolean breakBlock(Object pos) { return null; }
        default boolean cancelBlockBreaking() { return false; }
        default Boolean dropSelected(Object player, boolean entireStack) { return null; }
        default void serverBlockUpdate(Object world, Object pos, Object state) { }
        default void chunkUnloaded(Object world, long packedChunk) { }
        default boolean attackEntity(Object player) { return false; }
        default boolean packet(Object packet) { return false; }
        default void beforeSetBlockState(Object world, Object pos) { }
        default boolean creativeStack(Object stack, int slot) { return false; }
        default boolean creativeDrop(Object stack) { return false; }
    }
    private FakeGambleHookBridge() { }
    public static Registration install(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("A local inventory simulation runtime is already connected");
            return current = new Registration(handler);
        }
    }
    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(FakeGambleHookBridge.class.getName(), false, loader) != FakeGambleHookBridge.class) {
                throw new IllegalStateException("The game and payload must share the same local inventory simulation bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta local inventory simulation bridge", failure);
        }
    }
    public static boolean click(int syncId, int slotId, int button, Object actionType, Object player) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try { return registration.handler.click(syncId, slotId, button, actionType, player); }
            finally { registration.dispatches--; }
        }
    }
    public static boolean worldEvent(Object handler, int eventId, Object pos, int data) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try { return registration.handler.worldEvent(handler, eventId, pos, data); }
            finally { registration.dispatches--; }
        }
    }
    public static boolean pickup(Object item, Object player) { return dispatch(0, item, player); }
    public static void itemTick(Object item) { dispatch(1, item, null); }
    public static void afterSync() { dispatch(2, null, null); }
    public static void screenClosed(Object handler, Object player) { dispatch(3, handler, player); }
    public static void noteDispenser(Object hit) { dispatch(4, hit, null); }
    public static boolean pick(Object client) { return dispatch(5, client, null); }
    public static void screenRender(Object screen) { dispatch(6, screen, null); }
    // Null always means vanilla forwarding. Boxed false is a handled boolean result.
    public static Object interactBlock(Object player, Object hand, Object hit) { return ghost(0, player, hand, hit, 0); }
    public static Object interactItem(Object player, Object hand) { return ghost(1, player, hand, null, 0); }
    public static Object interactEntity(Object player, Object hand) { return ghost(2, player, hand, null, 0); }
    public static Boolean attackBlock(Object pos, Object direction) { return (Boolean) ghost(3, pos, direction, null, 0); }
    public static Boolean updateBlockBreakingProgress(Object pos, Object direction) { return (Boolean) ghost(4, pos, direction, null, 0); }
    public static Boolean breakBlock(Object pos) { return (Boolean) ghost(5, pos, null, null, 0); }
    public static boolean cancelBlockBreaking() { return Boolean.TRUE.equals(ghost(6, null, null, null, 0)); }
    public static Boolean dropSelected(Object player, boolean entireStack) { return (Boolean) ghost(7, player, null, null, entireStack ? 1 : 0); }
    public static void serverBlockUpdate(Object world, Object pos, Object state) { ghost(8, world, pos, state, 0); }
    public static void chunkUnloaded(Object world, long packedChunk) { ghost(9, world, null, null, packedChunk); }
    public static boolean attackEntity(Object player) { return Boolean.TRUE.equals(ghost(10, player, null, null, 0)); }
    public static boolean packet(Object packet) { return Boolean.TRUE.equals(ghost(11, packet, null, null, 0)); }
    public static void beforeSetBlockState(Object world, Object pos) { ghost(12, world, pos, null, 0); }
    public static boolean creativeStack(Object stack, int slot) { return Boolean.TRUE.equals(ghost(13, stack, null, null, slot)); }
    public static boolean creativeDrop(Object stack) { return Boolean.TRUE.equals(ghost(14, stack, null, null, 0)); }
    private static Object ghost(int operation, Object first, Object second, Object third, long value) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return null;
            registration.dispatches++;
            try {
                return switch (operation) {
                    case 0 -> registration.handler.interactBlock(first, second, third);
                    case 1 -> registration.handler.interactItem(first, second);
                    case 2 -> registration.handler.interactEntity(first, second);
                    case 3 -> registration.handler.attackBlock(first, second);
                    case 4 -> registration.handler.updateBlockBreakingProgress(first, second);
                    case 5 -> registration.handler.breakBlock(first);
                    case 6 -> registration.handler.cancelBlockBreaking();
                    case 7 -> registration.handler.dropSelected(first, value != 0);
                    case 8 -> { registration.handler.serverBlockUpdate(first, second, third); yield null; }
                    case 9 -> { registration.handler.chunkUnloaded(first, value); yield null; }
                    case 10 -> registration.handler.attackEntity(first);
                    case 11 -> registration.handler.packet(first);
                    case 12 -> { registration.handler.beforeSetBlockState(first, second); yield null; }
                    case 13 -> registration.handler.creativeStack(first, (int) value);
                    case 14 -> registration.handler.creativeDrop(first);
                    default -> throw new IllegalArgumentException("Unknown local block simulation callback");
                };
            } finally { registration.dispatches--; }
        }
    }
    private static boolean dispatch(int operation, Object first, Object second) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try {
                switch (operation) {
                    case 0 -> { return registration.handler.pickup(first, second); }
                    case 1 -> registration.handler.itemTick(first);
                    case 2 -> registration.handler.afterSync();
                    case 3 -> registration.handler.screenClosed(first, second);
                    case 4 -> registration.handler.noteDispenser(first);
                    case 5 -> { return registration.handler.pick(first); }
                    case 6 -> registration.handler.screenRender(first);
                    default -> throw new IllegalArgumentException("Unknown local inventory simulation callback");
                }
                return false;
            } finally { registration.dispatches--; }
        }
    }
    private static Registration active() {
        Registration registration = current;
        return registration != null && registration.thread == Thread.currentThread() ? registration : null;
    }
    public static final class Registration implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private Handler handler;
        private int dispatches;
        private boolean closed;
        private Registration(Handler handler) { this.handler = handler; }
        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                if (Thread.currentThread() != thread || dispatches != 0) {
                    throw new IllegalStateException("Disconnect local inventory simulation on the client thread outside a hook");
                }
                closed = true;
                handler = null;
                if (current == this) current = null;
            }
        }
    }
}
