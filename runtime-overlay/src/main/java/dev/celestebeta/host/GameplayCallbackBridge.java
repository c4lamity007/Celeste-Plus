package dev.celestebeta.host;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/** Game-owned JDK boundary for original server observations and scoped sword clicks. */
public final class GameplayCallbackBridge {
    private static Registration current;
    private static final ClassValue<MethodHandle> ATTACK = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            String name = switch (type.getName()) {
                case "net.minecraft.client.MinecraftClient" -> "doAttack";
                case "net.minecraft.class_310" -> "method_1536";
                default -> throw new IllegalArgumentException("Expected the Minecraft client class");
            };
            try {
                return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findVirtual(type, name, MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Original client attack is unavailable", failure); }
        }
    };
    public interface Handler {
        void serverUpdate(int kind, Object packet);
        boolean attack(Object client);
        Object mainHand(Object vanilla, Object hand);
        void entityAttack(Object target);
    }
    private GameplayCallbackBridge() { }
    public static void preflight(Class<?> clientClass) { ATTACK.get(clientClass); }
    public static synchronized Registration install(Handler handler) {
        if (current != null) throw new IllegalStateException("Gameplay callback runtime is already connected");
        return current = new Registration(Objects.requireNonNull(handler));
    }
    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(GameplayCallbackBridge.class.getName(), false, loader) != GameplayCallbackBridge.class) {
                throw new IllegalStateException("Game and payload need the canonical gameplay callback bridge");
            }
        } catch (ClassNotFoundException failure) { throw new IllegalStateException("Gameplay callback bridge is unavailable", failure); }
    }
    public static synchronized void serverUpdate(int kind, Object packet) {
        Registration registered = active();
        if (registered == null) return;
        registered.calls++;
        try { registered.handler.serverUpdate(kind, packet); }
        finally { registered.calls--; }
    }
    public static synchronized boolean attack(Object client) {
        Registration registered = active();
        if (registered == null) {
            try { return (boolean) ATTACK.get(client.getClass()).invokeExact(client); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Original client attack failed", failure); }
        }
        registered.calls++;
        try { return registered.handler.attack(client); }
        finally { registered.calls--; }
    }
    public static synchronized Object mainHand(Object vanilla, Object hand) {
        Registration registered = active();
        if (registered == null) return vanilla;
        registered.calls++;
        try { return registered.handler.mainHand(vanilla, hand); }
        finally { registered.calls--; }
    }
    public static synchronized void entityAttack(Object target) {
        Registration registered = active();
        if (registered == null) return;
        registered.calls++;
        try { registered.handler.entityAttack(target); }
        finally { registered.calls--; }
    }
    private static Registration active() { return current != null && current.thread == Thread.currentThread() ? current : null; }
    public static final class Registration implements AutoCloseable {
        private final Thread thread = Thread.currentThread();
        private Handler handler;
        private int calls;
        private boolean closed;
        private Registration(Handler handler) { this.handler = handler; }
        @Override public void close() {
            synchronized (GameplayCallbackBridge.class) {
                if (closed) return;
                if (thread != Thread.currentThread() || calls != 0) throw new IllegalStateException("Disconnect outside callbacks on the client thread");
                closed = true; handler = null;
                if (current == this) current = null;
            }
        }
    }
}
