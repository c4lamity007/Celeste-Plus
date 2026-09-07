package dev.celestebeta.host;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical JDK-only boundary for existing rotation redirects and input/stack hooks. */
public final class RotationHookBridge {
    private static final Object LOCK = new Object();
    private static Registration current;
    private static final ClassValue<Map<String, MethodHandle>> GETTERS = new ClassValue<>() {
        @Override protected Map<String, MethodHandle> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };

    public interface Handler {
        float outgoingYaw(Object entity);
        float outgoingPitch(Object entity);
        float itemYaw(Object entity);
        float itemPitch(Object entity);
        float movementYaw(Object entity);
        Object glidingVector(Object entity);
        float glidingPitch(Object entity);
        float sprintJumpYaw(Object entity);
        void keyboardTick(Object input);
        /** Null means vanilla; one element means cancel and return that exact value, even null. */
        Object[] selectedStack(Object inventory, Object replacement, boolean set);
    }

    private RotationHookBridge() { }

    public static Registration install(Handler handler) {
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (current != null) throw new IllegalStateException("A rotation runtime is already connected");
            return current = new Registration(handler);
        }
    }

    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(RotationHookBridge.class.getName(), false, loader) != RotationHookBridge.class) {
                throw new IllegalStateException("The game and payload must share the same rotation bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta rotation bridge", failure);
        }
    }

    public static void verifyVanillaAccess(Class<?> entity) {
        getter(entity, "getYaw", "method_36454", float.class);
        getter(entity, "getPitch", "method_36455", float.class);
        getter(entity, "getRotationVector", "method_5720", Object.class);
    }

    public static float outgoingYaw(Object entity) { return rotation(0, entity); }
    public static float outgoingPitch(Object entity) { return rotation(1, entity); }
    public static float itemYaw(Object entity) { return rotation(2, entity); }
    public static float itemPitch(Object entity) { return rotation(3, entity); }
    public static float movementYaw(Object entity) { return rotation(4, entity); }
    public static float glidingPitch(Object entity) { return rotation(5, entity); }
    public static float sprintJumpYaw(Object entity) { return rotation(6, entity); }

    private static float rotation(int operation, Object entity) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) {
                boolean pitch = operation == 1 || operation == 3 || operation == 5;
                try {
                    return (float) getter(entity.getClass(), pitch ? "getPitch" : "getYaw",
                        pitch ? "method_36455" : "method_36454", float.class).invokeExact(entity);
                } catch (Throwable failure) { throw propagate(failure); }
            }
            registration.dispatches++;
            try {
                return switch (operation) {
                    case 0 -> registration.handler.outgoingYaw(entity);
                    case 1 -> registration.handler.outgoingPitch(entity);
                    case 2 -> registration.handler.itemYaw(entity);
                    case 3 -> registration.handler.itemPitch(entity);
                    case 4 -> registration.handler.movementYaw(entity);
                    case 5 -> registration.handler.glidingPitch(entity);
                    case 6 -> registration.handler.sprintJumpYaw(entity);
                    default -> throw new IllegalArgumentException("Unknown rotation hook");
                };
            } finally { registration.dispatches--; }
        }
    }

    public static Object glidingVector(Object entity) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) {
                try { return (Object) getter(entity.getClass(), "getRotationVector", "method_5720", Object.class).invokeExact(entity); }
                catch (Throwable failure) { throw propagate(failure); }
            }
            registration.dispatches++;
            try { return registration.handler.glidingVector(entity); }
            finally { registration.dispatches--; }
        }
    }

    public static void keyboardTick(Object input) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return;
            registration.dispatches++;
            try { registration.handler.keyboardTick(input); }
            finally { registration.dispatches--; }
        }
    }

    public static Object[] selectedStack(Object inventory, Object replacement, boolean set) {
        synchronized (LOCK) {
            Registration registration = active();
            if (registration == null) return null;
            registration.dispatches++;
            try {
                Object[] result = registration.handler.selectedStack(inventory, replacement, set);
                if (result != null && result.length != 1) throw new IllegalStateException("Invalid selected-stack hook result");
                return result;
            } finally { registration.dispatches--; }
        }
    }

    private static Registration active() {
        Registration registration = current;
        return registration != null && registration.thread == Thread.currentThread() ? registration : null;
    }

    private static MethodHandle getter(Class<?> owner, String named, String intermediary, Class<?> result) {
        return GETTERS.get(owner).computeIfAbsent(named, ignored -> {
            Method selected = null;
            for (String name : new String[] {named, intermediary}) {
                try {
                    Method method = owner.getMethod(name);
                    if (Modifier.isStatic(method.getModifiers()) || result == float.class && method.getReturnType() != float.class
                            || result == Object.class && method.getReturnType().isPrimitive()) {
                        throw new IllegalStateException("Unexpected vanilla rotation getter: " + owner.getName() + "." + name);
                    }
                    if (selected != null) throw new IllegalStateException("Ambiguous vanilla rotation getter: " + owner.getName());
                    selected = method;
                } catch (NoSuchMethodException absent) {
                    // Resolve exactly one supported namespace, retaining virtual dispatch.
                }
            }
            if (selected == null) throw new IllegalStateException("Missing vanilla rotation getter: " + owner.getName() + "." + named);
            try { return MethodHandles.publicLookup().unreflect(selected).asType(MethodType.methodType(result, Object.class)); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Vanilla rotation getter is inaccessible", failure); }
        });
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Vanilla rotation getter failed", failure);
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
                    throw new IllegalStateException("Disconnect rotations on the client thread outside a hook");
                }
                closed = true;
                handler = null;
                if (current == this) current = null;
            }
        }
    }
}
