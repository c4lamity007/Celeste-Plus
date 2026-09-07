package dev.celestebeta.bootstrap.external;

import dev.celestebeta.host.instrument.CombinedHooksTransformer;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** One whole-class-set installation, using bytes observed from the actual game loader. */
final class HookInstallation {
    private final Instrumentation instrumentation;
    private final MinecraftTarget target;
    private final Map<Class<?>, byte[]> prepared = new IdentityHashMap<>();
    private final Map<Class<?>, String> originals = new IdentityHashMap<>();
    private final Map<Class<?>, String> canonicalOriginals = new IdentityHashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ClassFileTransformer transformer = new Applied();
    private volatile Consumer<Throwable> failureObserver = ignored -> { };
    private boolean installed;
    private boolean registered;
    private boolean needsRestore;
    private volatile boolean recordingInstall;
    private final Map<Class<?>, Integer> applied = new java.util.concurrent.ConcurrentHashMap<>();

    private HookInstallation(Instrumentation instrumentation, MinecraftTarget target) {
        this.instrumentation = instrumentation;
        this.target = target;
    }

    static HookInstallation prepare(Instrumentation instrumentation, MinecraftTarget target) throws Exception {
        HookInstallation installation = new HookInstallation(instrumentation, target);
        Map<Class<?>, byte[]> captured = new IdentityHashMap<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public synchronized byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                                                            ProtectionDomain domain, byte[] bytes) {
                if (loader == target.loader() && target.targets().contains(type)) {
                    if (captured.put(type, bytes.clone()) != null) installation.failure.compareAndSet(null, new IOException("Duplicate target capture"));
                }
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try { instrumentation.retransformClasses(target.targets().toArray(Class<?>[]::new)); }
        finally { instrumentation.removeTransformer(observer); }
        if (installation.failure.get() != null) throw new IOException("Target capture failed", installation.failure.get());
        for (Class<?> type : target.targets()) {
            byte[] bytes = captured.get(type);
            if (bytes == null) throw new IOException("Required target was not captured: " + type.getName());
            installation.originals.put(type, VerifiedBootstrapLoader.hash(bytes));
            installation.canonicalOriginals.put(type, ClassFileIdentity.canonicalHash(bytes));
            installation.prepared.put(type, CombinedHooksTransformer.prepare(type.getName().replace('.', '/'), bytes));
        }
        return installation;
    }

    synchronized void install() throws Exception {
        if (installed) throw new IllegalStateException("Hooks are already installed");
        if (failure.get() != null) throw new IOException("Hook preparation failed", failure.get());
        instrumentation.addTransformer(transformer, true);
        registered = true;
        recordingInstall = true;
        try {
            instrumentation.retransformClasses(target.targets().toArray(Class<?>[]::new));
            recordingInstall = false;
            for (Class<?> type : target.targets()) {
                if (applied.getOrDefault(type, 0) != 1) throw new IOException("Required hook target was not applied exactly once: " + type.getName());
            }
            installed = true;
            verifyReady();
        } catch (Exception | LinkageError error) {
            try { close(); }
            catch (Exception rollback) { error.addSuppressed(rollback); }
            throw error;
        } finally {
            recordingInstall = false;
        }
    }

    synchronized void verifyReady() throws IOException {
        Throwable prior = failure.get();
        if (prior != null) throw new IOException("Minecraft hook installation failed: " + prior.getMessage(), prior);
        if (!installed || !registered || needsRestore) throw new IOException("The complete Minecraft hook set is not active");
    }

    void onFailure(Consumer<Throwable> observer) {
        failureObserver = java.util.Objects.requireNonNull(observer);
        Throwable prior = failure.get();
        if (prior != null) observer.accept(prior);
    }

    synchronized void close() throws Exception {
        if (!registered && !needsRestore) return;
        installed = false;
        if (registered) {
            if (!instrumentation.removeTransformer(transformer)) throw new IOException("Hook transformer could not be removed; restart Minecraft");
            registered = false;
            needsRestore = true;
        }
        // Invoke the ordinary retransformation chain after removing only ours.
        // Never redefine from a cached vanilla or previously observed class file.
        instrumentation.retransformClasses(target.targets().toArray(Class<?>[]::new));
        needsRestore = false;
    }

    private final class Applied implements ClassFileTransformer {
        @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> type,
                                          ProtectionDomain domain, byte[] bytes) {
            if (loader != target.loader() || !originals.containsKey(type)) return null;
            if (recordingInstall) applied.merge(type, 1, Integer::sum);
            try {
                // Retransformation may rebuild constant-pool indices without changing code.
                // Preserve the raw fast path, then compare all understood class metadata.
                if (!originals.get(type).equals(VerifiedBootstrapLoader.hash(bytes))
                        && !canonicalOriginals.get(type).equals(ClassFileIdentity.canonicalHash(bytes))) {
                    throw new IOException("Another transformer changed a checked Minecraft target: " + name + "; restart required");
                }
            } catch (IOException | RuntimeException invalid) {
                IOException error = new IOException("Minecraft target identity check failed for " + name + ": " + invalid.getMessage(), invalid);
                if (failure.compareAndSet(null, error)) {
                    try { failureObserver.accept(error); }
                    catch (RuntimeException observerFailure) { error.addSuppressed(observerFailure); }
                }
                return null;
            }
            return prepared.get(type).clone();
        }
    }
}
