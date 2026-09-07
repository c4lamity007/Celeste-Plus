package dev.celestebeta.host;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Opt-in JDK-only linkage boundary for prepared Minecraft reload hooks.
 * Never installs instrumentation, discovers clients, or loads a payload.
 * Put one canonical definition where the actual game loader can resolve it.
 */
public final class ResourceReloadBridge {
    private static final Object LOCK = new Object();
    private static Registration installed;

    private ResourceReloadBridge() { }

    public interface Handler {
        List<?> augment(Object client, boolean forced, CompletableFuture<?> attempt, List<?> original);
        boolean beforeResult(Object client, boolean forced, CompletableFuture<?> attempt, Optional<?> failure);
        void afterResult(Object client, boolean forced, CompletableFuture<?> attempt,
                         Optional<?> failure, Throwable callbackFailure);
    }

    /** Bridge-origin errors, distinct from an exception thrown by Minecraft's callback body. */
    public static final class HookFailureException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private HookFailureException(String stage, Throwable cause) {
            super("Resource reload hook failed during " + stage, cause);
        }
    }

    /**
     * Required before eventual hook enablement; this is a check, not an installer.
     * Context-loader changes and system-loader placement alone are insufficient.
     */
    public static void assertVisibleFrom(ClassLoader targetLoader) {
        try {
            for (Class<?> required : new Class<?>[] { ResourceReloadBridge.class, Handler.class, Registration.class }) {
                if (Class.forName(required.getName(), false, targetLoader) != required) {
                    throw new IllegalStateException("Target loader does not resolve the canonical reload bridge");
                }
            }
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException("Target loader cannot resolve the canonical reload bridge", failure);
        }
    }

    /** Exactly one opt-in registration; client and attempt matching use reference identity. */
    public static Registration install(Object exactClient, Handler handler) {
        Objects.requireNonNull(exactClient, "exactClient");
        Objects.requireNonNull(handler, "handler");
        synchronized (LOCK) {
            if (installed != null) {
                throw new IllegalStateException("A reload bridge handler is already installed");
            }
            installed = new Registration(exactClient, handler);
            return installed;
        }
    }

    /**
     * No handler/other client returns the exact original list, without inspection.
     * Registered handlers receive an immutable shallow snapshot to prevent them
     * mutating selected inputs. A no-addition result maps back to original identity.
     * Only one appended, non-null entry is permitted; originals retain identity/order.
     * Handler/validation errors preserve original inputs and reach the result observer.
     */
    public static List<?> inputs(Object client, boolean forced, CompletableFuture<?> token, List<?> original) {
        Registration registration;
        Attempt attempt;
        synchronized (LOCK) {
            registration = matching(client);
            if (registration == null) return original;
            if (token == null) {
                report(new HookFailureException("inputs", new NullPointerException("attempt")));
                return original;
            }
            attempt = registration.attempts.get(token);
            if (attempt != null) {
                record(attempt, "duplicate inputs", new IllegalStateException("Attempt inputs were already observed"));
                return original;
            }
            attempt = new Attempt(registration, token, forced);
            registration.attempts.put(token, attempt);
            registration.dispatches++;
        }
        try {
            List<?> snapshot = List.copyOf(Objects.requireNonNull(original, "original"));
            List<?> result = Objects.requireNonNull(registration.handler.augment(client, forced, token, snapshot), "augmented inputs");
            if (result.size() < snapshot.size() || result.size() > snapshot.size() + 1) {
                throw new IllegalArgumentException("A hook may only append one internal resource view");
            }
            for (int i = 0; i < snapshot.size(); i++) {
                if (result.get(i) != snapshot.get(i)) {
                    throw new IllegalArgumentException("A hook changed selected resource identity/order");
                }
            }
            List<?> checked = List.copyOf(result);
            return checked.size() == snapshot.size() ? original : checked;
        } catch (Throwable failure) {
            synchronized (LOCK) {
                record(attempt, "inputs", failure);
            }
            return original;
        } finally {
            synchronized (LOCK) {
                registration.dispatches--;
            }
        }
    }

    /** Only an owned, present Throwable outcome can be handled instead of vanilla. */
    public static boolean beforeResult(Object client, boolean forced, CompletableFuture<?> token, Optional<?> failure) {
        Attempt attempt;
        synchronized (LOCK) {
            Registration registration = matching(client);
            attempt = registration == null ? null : registration.attempts.get(token);
            if (attempt == null || attempt.forced != forced) return false;
            if (attempt.callbackThread != null) {
                if (attempt.callbackThread == Thread.currentThread()) attempt.ignoredCallbacks++;
                return false;
            }
            if (attempt.awaitingFailureReplay && (failure == null || failure.isEmpty())) return false;
            attempt.awaitingFailureReplay = false;
            attempt.callbackThread = Thread.currentThread();
            attempt.outcome = failure;
            registration.dispatches++;
        }
        try {
            if (failure == null || failure.isPresent() && !(failure.get() instanceof Throwable)) {
                throw new IllegalArgumentException("Reload outcome must be an optional Throwable");
            }
            boolean handled = attempt.registration.handler.beforeResult(client, forced, token, failure);
            if (handled && failure.isEmpty()) {
                throw new IllegalStateException("A handler cannot skip a successful vanilla callback");
            }
            return handled;
        } catch (Throwable hookFailure) {
            synchronized (LOCK) {
                record(attempt, "beforeResult", hookFailure);
            }
            return false;
        } finally {
            synchronized (LOCK) {
                attempt.registration.dispatches--;
            }
        }
    }

    /**
     * No-throw observation boundary: Minecraft callback errors retain exact identity.
     * Hook-origin failures are marked and supplied only if no callback error exists,
     * otherwise suppressed onto it. A success-body throw retains ownership for the
     * immediate same-token error replay performed by SplashOverlay.tick; all other
     * completed callbacks release the token. Duplicate/unowned callbacks are no-ops.
     */
    public static void afterResult(Object client, boolean forced, CompletableFuture<?> token,
                                   Optional<?> failure, Throwable callbackFailure) {
        Attempt attempt;
        synchronized (LOCK) {
            Registration registration = matching(client);
            attempt = registration == null ? null : registration.attempts.get(token);
            if (attempt == null || attempt.forced != forced || attempt.callbackThread != Thread.currentThread()) return;
            if (attempt.ignoredCallbacks != 0) {
                attempt.ignoredCallbacks--;
                return;
            }
            if (attempt.outcome != failure) return;
            registration.dispatches++;
        }
        Throwable delivered = callbackFailure;
        try {
            HookFailureException hookFailure;
            synchronized (LOCK) {
                hookFailure = attempt.hookFailure;
                attempt.hookFailure = null;
            }
            if (hookFailure != null) {
                if (delivered == null) delivered = hookFailure;
                else suppress(delivered, hookFailure);
            }
            attempt.registration.handler.afterResult(client, forced, token, failure, delivered);
        } catch (Throwable observerFailure) {
            HookFailureException marked = new HookFailureException("afterResult", observerFailure);
            if (callbackFailure != null) suppress(callbackFailure, marked);
            report(marked);
        } finally {
            synchronized (LOCK) {
                attempt.registration.dispatches--;
                attempt.callbackThread = null;
                attempt.outcome = null;
                if (callbackFailure != null && failure != null && failure.isEmpty() && !attempt.replayed) {
                    attempt.awaitingFailureReplay = true;
                    attempt.replayed = true;
                } else {
                    attempt.registration.attempts.remove(token);
                }
            }
        }
    }

    private static Registration matching(Object client) {
        return installed != null && installed.client == client ? installed : null;
    }

    private static void record(Attempt attempt, String stage, Throwable failure) {
        HookFailureException marked = new HookFailureException(stage, failure);
        if (attempt.hookFailure == null) attempt.hookFailure = marked;
        else suppress(attempt.hookFailure, marked);
        report(marked);
    }

    private static void suppress(Throwable original, Throwable extra) {
        try {
            if (original != extra) original.addSuppressed(extra);
        } catch (Throwable ignored) {
            // Diagnostics must never replace the game's original Throwable.
        }
    }

    private static void report(Throwable failure) {
        try {
            // Keep the existing failure/fallback result without writing Minecraft logs.
        } catch (Throwable ignored) {
            // A logger failure must not affect vanilla dispatch or exception identity.
        }
    }

    public static final class Registration implements AutoCloseable {
        private Object client;
        private Handler handler;
        private final Map<CompletableFuture<?>, Attempt> attempts = new IdentityHashMap<>();
        private int dispatches;
        private boolean closed;

        private Registration(Object client, Handler handler) {
            this.client = client;
            this.handler = handler;
        }

        /**
         * Releases an exact input token when reload submission threw before its
         * overlay/result callback was installed. The host must establish that
         * no callback can still arrive; this is not cancellation of a live reload.
         * Asset/view retirement remains the host's responsibility.
         */
        public void abandonSubmission(CompletableFuture<?> token) {
            Objects.requireNonNull(token, "attempt");
            synchronized (LOCK) {
                if (closed || installed != this) {
                    throw new IllegalStateException("Reload registration is no longer active");
                }
                Attempt attempt = attempts.get(token);
                if (attempt == null || dispatches != 0 || attempt.callbackThread != null
                        || attempt.awaitingFailureReplay || attempt.replayed) {
                    throw new IllegalStateException("Only an unstarted exact reload submission can be abandoned");
                }
                attempts.remove(token);
            }
        }

        /** Close after terminal callbacks/queued host work, never from inside a dispatch. */
        @Override
        public void close() {
            synchronized (LOCK) {
                if (closed) return;
                if (dispatches != 0 || !attempts.isEmpty()) {
                    throw new IllegalStateException("Reload attempts or callback dispatches are still live");
                }
                closed = true;
                if (installed == this) installed = null;
                client = null;
                handler = null;
            }
        }
    }

    private static final class Attempt {
        private final Registration registration;
        private final boolean forced;
        private Thread callbackThread;
        private Optional<?> outcome;
        private HookFailureException hookFailure;
        private boolean awaitingFailureReplay;
        private boolean replayed;
        private int ignoredCallbacks;

        private Attempt(Registration registration, CompletableFuture<?> token, boolean forced) {
            this.registration = registration;
            this.forced = forced;
        }
    }
}
