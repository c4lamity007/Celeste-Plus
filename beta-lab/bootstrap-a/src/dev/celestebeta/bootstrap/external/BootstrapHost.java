package dev.celestebeta.bootstrap.external;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.c4lam.celeste.beta.delivery.BetaDeliveryClient;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticated first bootstrap. Entry is called only by the explicit Attach API agent. */
public final class BootstrapHost {
    private static Session current;
    private static boolean attempted;
    // Exact canonical bridges/JNI support are one resident owner for this JVM.
    private static ResidentSupport residentSupport;
    private BootstrapHost() { }

    /** The local handoff contains only the existing installer grant and device ID, never a signing key. */
    public static synchronized String command(Instrumentation instrumentation, String request, String residentDigest) throws Exception {
        JsonObject value = JsonParser.parseString(request).getAsJsonObject();
        String action = value.get("action").getAsString();
        if (action.equals("inspect")) {
            if (!value.keySet().equals(Set.of("action"))) throw new IOException("Invalid beta inspection request");
            return new com.google.gson.Gson().toJson(MinecraftTarget.describe(instrumentation));
        }
        if (action.equals("status") || action.equals("stop")) {
            if (!value.keySet().equals(Set.of("action"))) throw new IOException("Invalid beta control request");
            if (current == null) return attempted
                ? "{\"phase\":\"restart required\",\"error\":\"Restart Minecraft before attaching another release.\"}"
                : "{\"phase\":\"inactive\"}";
            if (action.equals("stop")) current.stop();
            return new com.google.gson.Gson().toJson(current.status());
        }
        if (!action.equals("start") || !value.keySet().equals(Set.of("action", "installer", "device", "uid", "support_directory", "expected_game_directory", "expected_release", "expected_catalog_sha256"))) {
            throw new IOException("Invalid beta start request");
        }
        long uid = requestUid(value);
        requireAvailable();
        Path expectedDirectory = Path.of(value.get("expected_game_directory").getAsString()).toRealPath();
        Path observedDirectory = Path.of(MinecraftTarget.describe(instrumentation).get("game_directory"));
        if (!Files.isSameFile(expectedDirectory, observedDirectory)) throw new IOException("Selected launcher instance and running Minecraft directory differ");
        current = start(instrumentation, value.get("installer").getAsString(), value.get("device").getAsString(), uid,
            Path.of(value.get("support_directory").getAsString()), residentDigest,
            value.get("expected_release").getAsString(), value.get("expected_catalog_sha256").getAsString());
        return new com.google.gson.Gson().toJson(current.status());
    }

    private static Session start(Instrumentation instrumentation, String installer, String device, long uid, Path supportRoot, String residentDigest,
                                 String expectedRelease, String expectedCatalog) throws Exception {
        MinecraftTarget target = MinecraftTarget.inspect(instrumentation);
        java.util.function.Consumer<Runnable> retirementQueue = retirementQueue(target.client());
        JsonObject trust = validatedTrust(resourceObject("META-INF/celeste-beta/bootstrap-trust.json", BootstrapHost.class.getClassLoader()));
        BetaDeliveryClient delivery = new BetaDeliveryClient(URI.create(trust.get("origin").getAsString()),
            Base64.getUrlDecoder().decode(trust.get("server_key").getAsString()), installer, device, uid);
        BetaDeliveryClient.VerifiedCatalog catalog = delivery.fetchCatalog();
        if (!catalog.releaseId().equals(expectedRelease) || !catalog.catalogSha256().equals(expectedCatalog)) {
            throw new IOException("Beta release changed after launcher verification. Authenticate again before attaching.");
        }
        Path ownJar = Path.of(BootstrapHost.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        BetaDeliveryClient.Artifact firstBootstrap = artifact(catalog, "bootstrap-a");
        if (!firstBootstrap.sha256().equals(residentDigest) || !Files.isRegularFile(ownJar) || Files.size(ownJar) != firstBootstrap.size()
            || !VerifiedBootstrapLoader.hash(Files.readAllBytes(ownJar)).equals(firstBootstrap.sha256())) {
            throw new IOException("Running first bootstrap does not match the signed release");
        }
        VerifiedBootstrapLoader internal = null;
        HookInstallation hooks = null;
        ResidentSupport support = null;
        boolean enteredPayload = false;
        try (BetaDeliveryClient.MemoryRelease release = delivery.download(catalog)) {
            byte[] b = copy(release.artifact("bootstrap-b"));
            try { internal = new VerifiedBootstrapLoader(b, artifact(catalog, "bootstrap-b").sha256(), BootstrapHost.class.getClassLoader()); }
            finally { Arrays.fill(b, (byte) 0); }
            JsonObject layout = resourceObject(VerifiedBootstrapLoader.LAYOUT, internal);
            Set<String> classes = strings(layout, "class_prefixes"), resources = strings(layout, "resource_prefixes");
            byte[] a = copy(release.artifact("payload-a")), second = null;
            try {
                second = copy(release.artifact("payload-b"));
                try (TwoPartMemoryClassLoader bytes = TwoPartMemoryClassLoader.open(a, artifact(catalog, "payload-a").sha256(),
                    second, artifact(catalog, "payload-b").sha256(), new TwoPartMemoryClassLoader.Namespaces(classes, resources),
                    new TwoPartMemoryClassLoader.Limits(64 * 1024 * 1024, 65_536, 8 * 1024 * 1024, 128L * 1024 * 1024), target.loader())) {
                    if (!resourceObject("META-INF/celeste-beta/bootstrap-trust.json", bytes).equals(trust)) {
                        throw new IOException("First bootstrap and retained payload trust profiles differ");
                    }
                    support = ResidentSupport.prepare(bytes, supportRoot, BootstrapHost.class.getClassLoader(), residentSupport);
                }
            } finally {
                Arrays.fill(a, (byte) 0);
                if (second != null) Arrays.fill(second, (byte) 0);
            }
            hooks = HookInstallation.prepare(instrumentation, target);
            // Preparation is reversible. From installation onward, admit another
            // lifetime only after observed payload cleanup and complete hook restore.
            attempted = true;
            residentSupport = support; // Keep partial irreversible support installation owned on failure.
            support.install(instrumentation, target.loader(), BootstrapHost.class.getClassLoader());
            hooks.install();
            HookInstallation checkedHooks = hooks;
            ResidentSupport checkedSupport = support;
            Class<?> bootstrap = Class.forName(VerifiedBootstrapLoader.PREFIX + "MemoryPayloadBootstrap", true, internal);
            Class<?> capability = Class.forName(VerifiedBootstrapLoader.PREFIX + "MemoryPayloadBootstrap$PreparedGame", true, internal);
            Object preparedGame = Proxy.newProxyInstance(internal, new Class<?>[] { capability }, (proxy, method, arguments) -> {
                return switch (method.getName()) {
                    case "verifyReadyForPayload" -> {
                        checkedHooks.verifyReady();
                        if (catalog.expiresAt() <= Instant.now().getEpochSecond()) throw new IOException("Beta delivery expired during preparation");
                        yield null;
                    }
                    case "runtimeParent" -> checkedSupport.loader();
                    case "toString" -> "Verified Minecraft beta preparation";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException("Unknown game capability method");
                };
            });
            // From this invocation onward the game may hold queued payload work.
            // An uncertain throwing entrypoint requires restart, not buffer erasure.
            enteredPayload = true;
            Object payload = invoke(bootstrap.getMethod("load", BetaDeliveryClient.MemoryRelease.class, capability), null, release, preparedGame);
            Session session = new Session(payload, internal, hooks, retirementQueue, catalog.releaseId());
            hooks.onFailure(error -> session.stop());
            return session;
        } finally {
            if (!enteredPayload) {
                try { if (hooks != null) hooks.close(); }
                finally { if (internal != null) internal.close(); }
            }
        }
    }

    private static long requestUid(JsonObject request) throws IOException {
        var value = request.get("uid");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
            || !value.getAsString().matches("[1-9][0-9]{0,18}")) {
            throw new IOException("Celeste+ UID must be a positive integer");
        }
        try { return Long.parseLong(value.getAsString()); }
        catch (NumberFormatException invalid) { throw new IOException("Celeste+ UID is out of range", invalid); }
    }

    private static JsonObject validatedTrust(JsonObject trust) throws IOException {
        Set<String> keys = Set.of("schema", "origin", "server_key", "product");
        if (trust == null || !trust.keySet().equals(keys)) throw new IOException("Celeste+ trust profile is invalid");
        for (String key : keys) {
            var value = trust.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) {
                throw new IOException("Celeste+ trust profile is invalid");
            }
        }
        if (!trust.get("schema").getAsString().equals("celeste-plus-bootstrap-trust-1")
            || !trust.get("product").getAsString().equals("celeste_plus")) {
            throw new IOException("Celeste+ trust profile is invalid");
        }
        return trust;
    }

    private static java.util.function.Consumer<Runnable> retirementQueue(Class<?> client) throws Exception {
        boolean intermediary = client.getName().equals("net.minecraft.class_310");
        Method getInstance = client.getMethod(intermediary ? "method_1551" : "getInstance");
        Method send = client.getMethod(intermediary ? "method_63588" : "send", Runnable.class);
        if (!java.lang.reflect.Modifier.isStatic(getInstance.getModifiers()) || getInstance.getReturnType() != client
            || java.lang.reflect.Modifier.isStatic(send.getModifiers()) || send.getReturnType() != void.class) {
            throw new IOException("Minecraft cleanup queue has an unexpected signature");
        }
        Object instance = invoke(getInstance, null);
        if (!client.isInstance(instance)) throw new IOException("Minecraft cleanup queue has no running client");
        java.lang.invoke.MethodHandle enqueue = java.lang.invoke.MethodHandles.lookup().unreflect(send).bindTo(instance)
            .asType(java.lang.invoke.MethodType.methodType(void.class, Runnable.class));
        return action -> {
            try { enqueue.invokeExact(action); }
            catch (Throwable failure) { throw new IllegalStateException("Minecraft cleanup queue rejected retirement", failure); }
        };
    }

    private static synchronized void requireAvailable() throws IOException {
        if (current != null && current.cleanStopped()) {
            // The retained status is JDK-only; clear it before creating fresh B/payload loaders.
            current = null;
            attempted = false;
        }
        if (attempted || current != null) {
            throw new IOException("The previous beta lifetime is active or cleanup is incomplete. Stop it successfully, or restart Minecraft after failed cleanup.");
        }
    }

    private static final class Session {
        private Object payload;
        private Method stop;
        private VerifiedBootstrapLoader internal;
        private HookInstallation hooks;
        private final String release;
        private final java.util.function.Consumer<Runnable> retirementQueue;
        private volatile String phase = "starting";
        private volatile String error = "";
        private boolean stopping, cleanStopped;
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();

        Session(Object payload, VerifiedBootstrapLoader internal, HookInstallation hooks, java.util.function.Consumer<Runnable> retirementQueue, String release) throws Exception {
            this.payload = payload; this.internal = internal; this.hooks = hooks; this.release = release;
            this.retirementQueue = java.util.Objects.requireNonNull(retirementQueue);
            Class<?> type = payload.getClass();
            stop = type.getMethod("stop");
            stage(invoke(type.getMethod("ready"), payload)).whenComplete((unused, failure) -> readyCompleted(failure));
            stage(invoke(type.getMethod("stopped"), payload)).whenComplete((unused, failure) -> queueRetirement(failure));
        }
        private synchronized void readyCompleted(Throwable failure) {
            if (cleanStopped || phase.equals("restart required")) return;
            if (failure == null) { if (!stopping) phase = "active"; }
            else { phase = "failed"; error = failureMessage(failure); }
        }
        private synchronized void queueRetirement(Throwable failure) {
            stopping = true; phase = "stopping";
            try {
                // Use Minecraft's always-enqueued send, never execute's inline path.
                // Leave B/payload completion callbacks before revoking B class resources.
                retirementQueue.accept(() -> finishStop(failure));
            } catch (RuntimeException | LinkageError rejected) {
                phase = "restart required"; error = failureMessage(rejected);
                stopped.completeExceptionally(rejected);
            }
        }
        private synchronized void finishStop(Throwable failure) {
            if (failure == null) {
                try {
                    hooks.close();
                    internal.close();
                    // Reflection Methods and B's handle retain their defining loaders.
                    // Release them only after all restoration and buffer retirement succeeds.
                    payload = null; stop = null; hooks = null; internal = null;
                    cleanStopped = true; phase = "stopped"; error = "";
                    stopped.complete(null);
                    return;
                } catch (Exception | LinkageError cleanupFailure) { failure = cleanupFailure; }
            }
            phase = "restart required"; error = failureMessage(failure);
            stopped.completeExceptionally(failure);
        }
        synchronized boolean cleanStopped() { return cleanStopped; }
        void stop() {
            Object target; Method method;
            synchronized (this) {
                if (cleanStopped || phase.equals("restart required")) return;
                stopping = true; phase = "stopping";
                target = payload; method = stop;
            }
            // Do not hold A's monitor across B callbacks and their cleanup completion.
            try { stage(invoke(method, target)); }
            catch (Exception | LinkageError failure) {
                synchronized (this) {
                    if (!cleanStopped) { phase = "restart required"; error = failureMessage(failure); }
                }
            }
        }
        synchronized Map<String, String> status() { return Map.of("phase", phase, "release", release, "error", error); }
    }

    private static String failureMessage(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof InvocationTargetException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 1000));
    }

    private static CompletionStage<Void> stage(Object value) throws IOException {
        if (!(value instanceof CompletionStage<?> stage)) throw new IOException("Bootstrap lifecycle result is invalid");
        return stage.thenApply(unused -> (Void) null);
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(failure.getCause());
        }
    }
    private static JsonObject resourceObject(String name, ClassLoader loader) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Beta packaged metadata is missing: " + name);
            byte[] bytes = stream.readNBytes(65_537);
            if (bytes.length == 0 || bytes.length > 65_536) throw new IOException("Beta packaged metadata exceeds bounds");
            try { return JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException error) { throw new IOException("Beta packaged metadata is invalid", error); }
        }
    }
    private static Set<String> strings(JsonObject value, String name) throws IOException {
        Set<String> strings = new HashSet<>();
        for (var item : value.getAsJsonArray(name)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !strings.add(item.getAsString())) {
                throw new IOException("Invalid packaged namespace mapping");
            }
        }
        return Set.copyOf(strings);
    }
    private static BetaDeliveryClient.Artifact artifact(BetaDeliveryClient.VerifiedCatalog catalog, String id) throws IOException {
        return catalog.artifacts().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> new IOException("Signed beta artifact is absent"));
    }
    private static byte[] copy(ByteBuffer buffer) { byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes; }
}
