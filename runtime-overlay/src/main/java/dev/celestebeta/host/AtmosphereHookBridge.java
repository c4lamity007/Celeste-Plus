package dev.celestebeta.host;

import java.util.Objects;

/** Canonical JDK-only boundary for fog consumption and animated lightmap uploads. */
public final class AtmosphereHookBridge {
    private static final Object LOCK = new Object();
    private static Registration current;

    public interface Handler {
        boolean refreshLightmap();
        float nightVision(float vanilla);
        float gamma(float vanilla);
        void sharedFog(boolean enabled, Object camera, Object world, Object data, Object color);
        /** Six original upload distances; only the environmental pair is adjusted by Celeste. */
        void vanillaFog(boolean enabled, Object camera, Object world, Object color, float[] distances);
    }

    private AtmosphereHookBridge() { }
    public static Registration install(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("An atmosphere runtime is already connected");
            return current = new Registration(handler);
        }
    }
    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(AtmosphereHookBridge.class.getName(), false, loader) != AtmosphereHookBridge.class) {
                throw new IllegalStateException("The game and payload must share the same atmosphere bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta atmosphere bridge", failure);
        }
    }
    public static boolean refreshLightmap() {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return false;
            registration.dispatches++;
            try { return registration.handler.refreshLightmap(); }
            finally { registration.dispatches--; }
        }
    }
    public static float nightVision(float vanilla) { return lightmap(vanilla, true); }
    public static float gamma(float vanilla) { return lightmap(vanilla, false); }
    private static float lightmap(float vanilla, boolean nightVision) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return vanilla;
            registration.dispatches++;
            try { return nightVision ? registration.handler.nightVision(vanilla) : registration.handler.gamma(vanilla); }
            finally { registration.dispatches--; }
        }
    }
    public static void sharedFog(boolean enabled, Object camera, Object world, Object data, Object color) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return;
            registration.dispatches++;
            try { registration.handler.sharedFog(enabled, camera, world, data, color); }
            finally { registration.dispatches--; }
        }
    }
    public static void vanillaFog(boolean enabled, Object camera, Object world, Object color, float[] distances) {
        if (distances == null || distances.length != 6) throw new IllegalArgumentException("Expected six fog upload distances");
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return;
            registration.dispatches++;
            try { registration.handler.vanillaFog(enabled, camera, world, color, distances); }
            finally { registration.dispatches--; }
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
                    throw new IllegalStateException("Disconnect atmosphere on the client thread outside a hook");
                }
                closed = true;
                handler = null;
                if (current == this) current = null;
            }
        }
    }
}
