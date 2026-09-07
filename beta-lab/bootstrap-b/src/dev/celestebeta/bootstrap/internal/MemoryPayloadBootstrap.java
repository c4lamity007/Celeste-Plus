package dev.celestebeta.bootstrap.internal;

import dev.c4lam.celeste.beta.delivery.BetaDeliveryClient;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** The second bootstrap composes verified delivery with the actual payload transaction. */
public final class MemoryPayloadBootstrap {
    private static final String LAYOUT_RESOURCE = "META-INF/celeste-beta/runtime-layout.json";
    private MemoryPayloadBootstrap() { }

    /** Embedded in the signed second bootstrap after packaging/obfuscation; never taken from UI input. */
    public record RuntimeLayout(Set<String> classPrefixes, Set<String> resourcePrefixes,
                                String entryClass, String startMethod, String readyMethod,
                                String stoppedMethod, String stopMethod, String statusMethod) {
        public RuntimeLayout {
            classPrefixes = Set.copyOf(classPrefixes);
            resourcePrefixes = Set.copyOf(resourcePrefixes);
            new TwoPartMemoryClassLoader.Namespaces(classPrefixes, resourcePrefixes);
            if (resourcePrefixes.stream().noneMatch(LAYOUT_RESOURCE::startsWith)) {
                throw new IllegalArgumentException("Payload must own its retained runtime mapping");
            }
            if (!entryClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
                || classPrefixes.stream().noneMatch(entryClass::startsWith)) throw new IllegalArgumentException("Payload entrypoint is not owned");
            for (String method : new String[] { startMethod, readyMethod, stoppedMethod, stopMethod, statusMethod }) {
                if (!method.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid payload method mapping");
            }
            if (classPrefixes.stream().anyMatch(prefix -> prefix.startsWith("dev.celestebeta.") || "dev.celestebeta.".startsWith(prefix))) {
                throw new IllegalArgumentException("Payload cannot own the resident bridge/bootstrap package");
            }
        }
    }

    /**
     * Bootstrap A owns this capability and verifies the selected game, complete
     * method-hook coverage, absence of another Celeste runtime, resident library
     * digests and canonical bridge identities before permitting activation.
     */
    public interface PreparedGame {
        void verifyReadyForPayload() throws Exception;
        ClassLoader runtimeParent();
    }

    /** Release entrypoint: mapping is part of the verified bootstrap-B artifact. */
    public static Session load(BetaDeliveryClient.MemoryRelease release, PreparedGame game) throws Exception {
        try (InputStream stream = MemoryPayloadBootstrap.class.getResourceAsStream("/" + LAYOUT_RESOURCE)) {
            return load(release, readLayout(stream), game);
        }
    }

    public static Session load(BetaDeliveryClient.MemoryRelease release, RuntimeLayout layout,
                               PreparedGame game) throws Exception {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(game, "game");
        game.verifyReadyForPayload();
        BetaDeliveryClient.VerifiedCatalog catalog = release.catalog();
        if (catalog.expiresAt() <= Instant.now().getEpochSecond()) throw new IOException("Verified delivery catalog expired before startup");
        BetaDeliveryClient.Artifact first = artifact(catalog, "payload-a"), second = artifact(catalog, "payload-b");
        byte[] a = copy(release.artifact("payload-a"), first.size());
        byte[] b = null;
        TwoPartMemoryClassLoader loader = null;
        boolean started = false;
        try {
            b = copy(release.artifact("payload-b"), second.size());
            loader = TwoPartMemoryClassLoader.open(a, first.sha256(), b, second.sha256(),
                new TwoPartMemoryClassLoader.Namespaces(layout.classPrefixes(), layout.resourcePrefixes()),
                new TwoPartMemoryClassLoader.Limits(64 * 1024 * 1024, 65_536, 8 * 1024 * 1024, 128L * 1024 * 1024),
                Objects.requireNonNull(game.runtimeParent(), "game/support parent"));
            try (InputStream stream = loader.getResourceAsStream(LAYOUT_RESOURCE)) {
                if (!layout.equals(readLayout(stream))) throw new IOException("Bootstrap mapping and retained payload mapping differ");
            }
            Class<?> entry = Class.forName(layout.entryClass(), false, loader);
            if (entry.getClassLoader() != loader) throw new IOException("Payload entrypoint resolved outside its verified memory loader");
            Method start = entry.getMethod(layout.startMethod(), Consumer.class, String.class, String.class);
            Method ready = entry.getMethod(layout.readyMethod());
            Method stopped = entry.getMethod(layout.stoppedMethod());
            Method stop = entry.getMethod(layout.stopMethod());
            Method status = entry.getMethod(layout.statusMethod());
            if (!java.lang.reflect.Modifier.isStatic(start.getModifiers()) || start.getReturnType() != entry
                || java.lang.reflect.Modifier.isStatic(ready.getModifiers())
                || java.lang.reflect.Modifier.isStatic(stopped.getModifiers())
                || java.lang.reflect.Modifier.isStatic(stop.getModifiers())
                || java.lang.reflect.Modifier.isStatic(status.getModifiers())
                || !CompletionStage.class.isAssignableFrom(ready.getReturnType())
                || !CompletionStage.class.isAssignableFrom(stopped.getReturnType())
                || !CompletionStage.class.isAssignableFrom(stop.getReturnType()) || !Map.class.isAssignableFrom(status.getReturnType())) {
                throw new IOException("Payload lifecycle mapping is incompatible");
            }
            TwoPartMemoryClassLoader retained = loader;
            Consumer<Consumer<Map<String, byte[]>>> source = visitor -> {
                try { retained.visitRetainedParts(visitor::accept); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            };
            // From this point a throwing entrypoint may already have queued game
            // work. Do not invalidate its retained bytes on an uncertain outcome.
            started = true;
            Object runtime = invoke(start, null, source, catalog.catalogSha256(), catalog.runtimeSha256());
            // The payload entrypoint always returns its lifecycle handle before
            // queued game work. Host can now observe asynchronous failure/stop.
            Session session = new Session(retained, runtime, ready, stopped, stop, status);
            return session;
        } finally {
            Arrays.fill(a, (byte) 0);
            if (b != null) Arrays.fill(b, (byte) 0);
            if (!started && loader != null) loader.close();
        }
    }

    public static final class Session {
        private TwoPartMemoryClassLoader loader;
        private Object runtime;
        private Method stop, status;
        private volatile CompletionStage<Void> ready;
        private final CompletableFuture<Void> stoppedResult = new CompletableFuture<>();
        private final CompletionStage<Void> stopped = stoppedResult.minimalCompletionStage();
        private Map<String, String> terminalStatus;

        private Session(TwoPartMemoryClassLoader loader, Object runtime, Method ready, Method stopped,
                        Method stop, Method status) throws Exception {
            this.loader = loader; this.runtime = Objects.requireNonNull(runtime, "payload handle");
            this.stop = stop; this.status = status;
            this.ready = stage(invoke(ready, runtime));
            // Failed cleanup retains the payload owner and bytes. A clean stop
            // retires every B -> payload reference, including reflection Methods.
            stage(invoke(stopped, runtime)).whenComplete((unused, failure) -> retire(failure));
        }
        private void retire(Throwable failure) {
            if (failure == null) {
                try {
                    synchronized (this) {
                        loader.close();
                        boolean wasReady = ready.toCompletableFuture().isDone()
                            && !ready.toCompletableFuture().isCompletedExceptionally();
                        ready = wasReady ? CompletableFuture.<Void>completedFuture(null).minimalCompletionStage()
                            : CompletableFuture.<Void>failedFuture(new IOException("Payload stopped before ready")).minimalCompletionStage();
                        terminalStatus = Map.of("phase", "stopped");
                        loader = null; runtime = null; stop = null; status = null;
                    }
                    stoppedResult.complete(null);
                    return;
                } catch (RuntimeException | LinkageError cleanupFailure) { failure = cleanupFailure; }
            }
            stoppedResult.completeExceptionally(failure);
        }
        public CompletionStage<Void> ready() { return ready; }
        public CompletionStage<Void> stopped() { return stopped; }
        public synchronized CompletionStage<Void> stop() {
            if (terminalStatus != null || stoppedResult.isCompletedExceptionally()) return stopped;
            try { stage(invoke(stop, runtime)); return stopped; }
            catch (Exception | LinkageError error) { return CompletableFuture.failedFuture(error); }
        }
        public synchronized Map<String, String> status() throws Exception {
            if (terminalStatus != null) return terminalStatus;
            Object observed = invoke(status, runtime);
            if (!(observed instanceof Map<?, ?> map)) throw new IOException("Invalid payload status");
            var copy = new java.util.HashMap<String, String>();
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key) || !(item.getValue() instanceof String value)) {
                    throw new IOException("Payload status must contain string values");
                }
                copy.put(key, value);
            }
            return Map.copyOf(copy);
        }
    }

    private static CompletionStage<Void> stage(Object value) throws IOException {
        if (!(value instanceof CompletionStage<?> stage)) throw new IOException("Invalid payload lifecycle observation");
        return stage.thenApply(unused -> (Void) null).toCompletableFuture().minimalCompletionStage();
    }
    private static Object invoke(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        }
    }
    private static BetaDeliveryClient.Artifact artifact(BetaDeliveryClient.VerifiedCatalog catalog, String id) throws IOException {
        return catalog.artifacts().stream().filter(item -> item.id().equals(id) && item.role().equals("payload"))
            .findFirst().orElseThrow(() -> new IOException("Verified payload artifact missing"));
    }
    private static byte[] copy(ByteBuffer bytes, int expectedSize) throws IOException {
        if (bytes.remaining() != expectedSize || expectedSize <= 0 || expectedSize > BetaDeliveryClient.MAX_ARTIFACT_BYTES) {
            throw new IOException("Verified payload size changed");
        }
        byte[] result = new byte[expectedSize]; bytes.get(result); return result;
    }

    private static RuntimeLayout readLayout(InputStream stream) throws IOException {
        if (stream == null) throw new IOException("Packaged runtime layout is absent");
        byte[] data = stream.readNBytes(65_537);
        if (data.length == 0 || data.length > 65_536) throw new IOException("Packaged runtime layout exceeds bounds");
        try {
            JsonObject value = JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!value.keySet().equals(Set.of("schema", "class_prefixes", "resource_prefixes", "entry_class", "start_method",
                "ready_method", "stopped_method", "stop_method", "status_method"))
                || !value.get("schema").getAsString().equals("celeste-beta-runtime-layout-1")) {
                throw new IOException("Unsupported packaged runtime layout");
            }
            return new RuntimeLayout(strings(value, "class_prefixes"), strings(value, "resource_prefixes"),
                value.get("entry_class").getAsString(), value.get("start_method").getAsString(), value.get("ready_method").getAsString(),
                value.get("stopped_method").getAsString(), value.get("stop_method").getAsString(), value.get("status_method").getAsString());
        } catch (RuntimeException error) { throw new IOException("Invalid packaged runtime layout", error); }
    }
    private static Set<String> strings(JsonObject value, String field) throws IOException {
        Set<String> result = new java.util.HashSet<>();
        for (var item : value.getAsJsonArray(field)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !result.add(item.getAsString())) {
                throw new IOException("Invalid namespace in runtime layout");
            }
        }
        return Set.copyOf(result);
    }
}
