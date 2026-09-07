package dev.celestebeta.host;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Reusable game-owned event proxies whose payload listeners can be detached without editing Fabric arrays. */
public final class DetachableCallbacks {
    private static final Map<Object, Map<String, Slot>> SLOTS = new IdentityHashMap<>();

    private DetachableCallbacks() { }

    /**
     * Owners and listener interfaces must belong to the game, not the payload. A stable owner/name
     * registers one proxy for the game lifetime. Subsequent runtimes reuse it after the old work drains.
     * Only abstract void callbacks are supported; interface default methods retain their normal behavior.
     * The registrar is invoked synchronously once and is never retained by this bridge.
     */
    public static <T> Registration connect(Object owner, String name, Class<T> type, T listener,
                                           Consumer<? super T> registrar) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(registrar, "registrar");
        if (name.isBlank() || !type.isInterface() || !Modifier.isPublic(type.getModifiers()) || !type.isInstance(listener)) {
            throw new IllegalArgumentException("A named public listener interface and matching listener are required");
        }
        for (Method method : type.getMethods()) {
            if (Modifier.isAbstract(method.getModifiers()) && method.getReturnType() != void.class) {
                throw new IllegalArgumentException("Only abstract void callbacks are detachable: " + method);
            }
        }
        Slot slot;
        boolean first;
        synchronized (SLOTS) {
            Map<String, Slot> ownerSlots = SLOTS.computeIfAbsent(owner, ignored -> new HashMap<>());
            slot = ownerSlots.get(name);
            first = slot == null;
            if (first) {
                slot = new Slot(name, type);
                ownerSlots.put(name, slot);
            } else if (slot.type != type) {
                throw new IllegalStateException("Callback slot interface changed: " + name);
            }
        }
        Registration registration = slot.attach(listener, first);
        if (first) {
            try {
                registrar.accept(type.cast(slot.proxy));
                synchronized (slot) { slot.registered = true; }
            } catch (RuntimeException | Error failure) {
                // Registration may have partly succeeded; never create a duplicate proxy on retry.
                synchronized (slot) { slot.registrationFailed = true; }
                registration.close();
                throw failure;
            }
        }
        return registration;
    }

    public static void assertVisibleFrom(ClassLoader loader) {
        try {
            if (Class.forName(DetachableCallbacks.class.getName(), false, loader) != DetachableCallbacks.class) {
                throw new IllegalStateException("The game and payload must share the same callback bridge");
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The game cannot resolve the beta callback bridge", failure);
        }
    }

    public static final class Registration implements AutoCloseable {
        private final Slot slot;
        private final Attachment attachment;

        private Registration(Slot slot, Attachment attachment) { this.slot = slot; this.attachment = attachment; }

        /**
         * Stops new payload calls immediately, without blocking or cancelling an already executing call.
         * Safe from inside the listener itself. Wait for detached() elsewhere before reclaiming the runtime.
         */
        @Override public void close() {
            boolean drained;
            synchronized (slot) {
                attachment.closed = true;
                attachment.listener = null;
                drained = attachment.calls == 0;
            }
            if (drained) attachment.detached.complete(null);
        }

        /** Completes after close and the return/completion of this attachment's in-flight calls. */
        public CompletionStage<Void> detached() { return attachment.detached.minimalCompletionStage(); }
    }

    private static final class Attachment {
        private Object listener;
        private int calls;
        private boolean closed;
        private final CompletableFuture<Void> detached = new CompletableFuture<>();

        private Attachment(Object listener) { this.listener = listener; }
    }

    /** All fields on this permanently registered handler refer only to game/JDK objects after detach. */
    private static final class Slot implements InvocationHandler {
        private final String name;
        private final Class<?> type;
        private final Object proxy;
        private Attachment attachment;
        private int calls;
        private boolean registered;
        private boolean registrationFailed;

        private Slot(String name, Class<?> type) {
            this.name = name;
            this.type = type;
            proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this);
        }

        private synchronized Registration attach(Object listener, boolean first) {
            if (registrationFailed || !first && !registered) {
                throw new IllegalStateException("Callback registration did not finish: " + name);
            }
            if (calls != 0 || attachment != null && !attachment.closed) {
                throw new IllegalStateException("The previous callback must be detached and drained: " + name);
            }
            attachment = new Attachment(listener);
            return new Registration(this, attachment);
        }

        @Override public Object invoke(Object ignoredProxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Celeste beta callback (" + name + ")";
                    default -> throw new UnsupportedOperationException(method.toString());
                };
            }
            boolean defaultMethod = method.isDefault();
            Object listener;
            Dispatch dispatch;
            synchronized (this) {
                Attachment active = attachment != null && !attachment.closed ? attachment : null;
                listener = active == null ? null : active.listener;
                if (!defaultMethod && listener == null) return null;
                calls++;
                if (active != null) active.calls++;
                dispatch = new Dispatch(this, active);
            }
            try {
                // Running default methods on the proxy keeps queued default-method work game-owned.
                Object result = defaultMethod ? InvocationHandler.invokeDefault(proxy, method, arguments)
                    : method.invoke(listener, arguments);
                if (result instanceof CompletionStage<?> pending) {
                    pending.whenComplete((value, failure) -> dispatch.finish());
                } else {
                    dispatch.finish();
                }
                return result;
            } catch (InvocationTargetException failure) {
                dispatch.finish();
                throw failure.getCause();
            } catch (Throwable failure) {
                dispatch.finish();
                throw failure;
            }
        }
    }

    private static final class Dispatch {
        private final Slot slot;
        private final Attachment attachment;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Dispatch(Slot slot, Attachment attachment) { this.slot = slot; this.attachment = attachment; }

        private void finish() {
            if (!completed.compareAndSet(false, true)) return;
            boolean drained = false;
            synchronized (slot) {
                slot.calls--;
                if (attachment != null) {
                    attachment.calls--;
                    drained = attachment.closed && attachment.calls == 0;
                }
            }
            if (drained) attachment.detached.complete(null);
        }
    }
}
