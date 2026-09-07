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
