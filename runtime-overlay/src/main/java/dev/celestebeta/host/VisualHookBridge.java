package dev.celestebeta.host;

import java.util.Objects;

/** JDK-only boundary shared by prepared game methods and one active visual runtime. */
public final class VisualHookBridge {
    private static final Object LOCK = new Object();
    private static Registration current;

    public interface Handler {
        void extractSky(Object renderer, Object world, Object camera, Object state);
        boolean drawSky(Object renderer);
        boolean ownsSky(Object renderer);
        int swingDuration(Object entity, int vanilla);
        default void flushGui() { }
        default boolean hideHud(Object hud, Object context, Object counter) { return false; }
        default boolean mediaClick(Object click) { return false; }
        default void applyRotations(Object entity, Object state) { }
        default boolean replacePlayerModel(Object state) { return false; }
    }

    private VisualHookBridge() { }

    /** Install on the client's thread, after the host has verified the game hooks. */
    public static Registration install(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("A beta visual runtime is already connected");
            current = new Registration(Thread.currentThread(), handler);
            return current;
        }
    }

    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(VisualHookBridge.class.getName(), false, loader) != VisualHookBridge.class) {
                throw new IllegalStateException("The game and payload must share the same visual bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta visual bridge", failure);
        }
    }

    public static void extractSky(Object renderer, Object world, Object camera, Object state) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return;
            registration.dispatches++;
            try { registration.handler.extractSky(renderer, world, camera, state); }
            catch (RuntimeException | LinkageError failure) { registration.failed(failure); }
            finally { registration.dispatches--; }
        }
    }

    public static boolean drawSky(Object renderer) { return sky(renderer, true); }
    public static boolean ownsSky(Object renderer) { return sky(renderer, false); }

    private static boolean sky(Object renderer, boolean draw) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try { return draw ? registration.handler.drawSky(renderer) : registration.handler.ownsSky(renderer); }
            catch (RuntimeException | LinkageError failure) { registration.failed(failure); return false; }
            finally { registration.dispatches--; }
        }
    }

    public static int swingDuration(Object entity, int vanilla) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return vanilla;
            registration.dispatches++;
            try {
                int result = registration.handler.swingDuration(entity, vanilla);
                if (result != vanilla && result < 1) throw new IllegalStateException("Invalid visual swing duration");
                return result;
            } catch (RuntimeException | LinkageError failure) {
                registration.failed(failure);
                return vanilla;
            } finally { registration.dispatches--; }
        }
    }

    public static void flushGui() { presentation(0, null, null, null); }
    public static boolean hideHud(Object hud, Object context, Object counter) { return presentation(1, hud, context, counter); }
    public static boolean mediaClick(Object click) { return presentation(2, click, null, null); }
    public static void applyRotations(Object entity, Object state) { presentation(3, entity, state, null); }
    public static boolean replacePlayerModel(Object state) { return presentation(4, state, null, null); }

    private static boolean presentation(int operation, Object first, Object second, Object third) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try {
                switch (operation) {
                    case 0 -> registration.handler.flushGui();
                    case 1 -> { return registration.handler.hideHud(first, second, third); }
                    case 2 -> { return registration.handler.mediaClick(first); }
                    case 3 -> registration.handler.applyRotations(first, second);
                    case 4 -> { return registration.handler.replacePlayerModel(first); }
                    default -> throw new IllegalArgumentException("Unknown presentation operation");
                }
                return false;
            } catch (RuntimeException | LinkageError failure) {
                registration.failed(failure);
                return false;
            } finally { registration.dispatches--; }
        }
    }

    private static Registration active() {
        Registration registration = current;
        // Integrated-server and worker calls must keep their original behavior.
        return registration != null && registration.handler != null
            && registration.thread == Thread.currentThread() ? registration : null;
    }

    public static final class Registration implements AutoCloseable {
        private final Thread thread;
        private Handler handler;
        private int dispatches;
        private boolean closed;
        private boolean failed;

        private Registration(Thread thread, Handler handler) { this.thread = thread; this.handler = handler; }

        public boolean hasFailed() { synchronized (LOCK) { return failed; } }

        private void failed(Throwable failure) {
            failed = true;
            handler = null;
            // Keep the existing failure/fallback result without writing Minecraft logs.
        }

        @Override public void close() {
            synchronized (LOCK) {
                if (closed) return;
                if (Thread.currentThread() != thread || dispatches != 0) {
                    throw new IllegalStateException("Disconnect visuals on the client thread outside a game callback");
                }
                handler = null;
                closed = true;
                if (current == this) current = null;
            }
        }
    }
}
