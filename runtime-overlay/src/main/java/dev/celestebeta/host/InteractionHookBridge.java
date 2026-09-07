package dev.celestebeta.host;

import java.util.Objects;

/** Client-thread boundary for interaction hooks, including paired breaking exit cleanup. */
public final class InteractionHookBridge {
    private static final Object LOCK = new Object();
    private static Registration current;

    public interface Handler {
        int selectedSlot(Object inventory, int vanilla);
        void beginBreaking(Object manager);
        void endBreaking(Object manager);
        void beforeAttack(Object manager, Object player);
        void afterAttack(Object manager, Object target);
        void beforeItem(Object manager, Object player, Object hand);
        void afterItem(Object manager, Object result);
    }

    private InteractionHookBridge() { }

    public static Registration install(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("An interaction runtime is already connected");
            return current = new Registration(handler);
        }
    }

    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(InteractionHookBridge.class.getName(), false, loader) != InteractionHookBridge.class) {
                throw new IllegalStateException("The game and payload must share the same interaction bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta interaction bridge", failure);
        }
    }

    public static int selectedSlot(Object inventory, int vanilla) { return dispatch(0, inventory, null, null, vanilla); }
    public static void beginBreaking(Object manager) { dispatch(1, manager, null, null, 0); }
    public static void endBreaking(Object manager) { dispatch(2, manager, null, null, 0); }
    public static void beforeAttack(Object manager, Object player) { dispatch(3, manager, player, null, 0); }
    public static void afterAttack(Object manager, Object target) { dispatch(4, manager, target, null, 0); }
    public static void beforeItem(Object manager, Object player, Object hand) { dispatch(5, manager, player, hand, 0); }
    public static void afterItem(Object manager, Object result) { dispatch(6, manager, result, null, 0); }

    private static int dispatch(int operation, Object first, Object second, Object third, int vanilla) {
        synchronized (LOCK) {
            Registration registration = current;
            if (registration == null || registration.thread != Thread.currentThread()) return vanilla;
            registration.dispatches++;
            try {
                switch (operation) {
                    case 0 -> { return registration.handler.selectedSlot(first, vanilla); }
                    case 1 -> registration.handler.beginBreaking(first);
                    case 2 -> registration.handler.endBreaking(first);
                    case 3 -> registration.handler.beforeAttack(first, second);
                    case 4 -> registration.handler.afterAttack(first, second);
                    case 5 -> registration.handler.beforeItem(first, second, third);
                    case 6 -> registration.handler.afterItem(first, second);
                    default -> throw new IllegalArgumentException("Unknown interaction hook");
                }
                return vanilla;
            } finally {
                // Dispatch never changes an exception or invokes another hook.
                // The caller owns normal/exceptional exit pairing.
                registration.dispatches--;
            }
        }
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
                    throw new IllegalStateException("Disconnect interactions on the client thread outside a hook");
                }
                closed = true;
                handler = null;
                if (current == this) current = null;
            }
        }
    }
}
