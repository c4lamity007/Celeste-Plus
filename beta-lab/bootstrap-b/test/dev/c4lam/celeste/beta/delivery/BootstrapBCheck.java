package dev.c4lam.celeste.beta.delivery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.c4lam.celeste.auth.ArtifactIntegrity;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import dev.celestebeta.bootstrap.internal.MemoryPayloadBootstrap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.minecraft.client.MinecraftClient;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Real signed delivery, memory-loader and payload-session composition; game/auth edges are controlled stubs. */
public final class BootstrapBCheck {
    private static final String ENTRY = "dev.c4lam.celeste.beta.lifecycle.BetaPayloadSession";
    private static final String ENTRY_PATH = ENTRY.replace('.', '/') + ".class";
    private static final String LAYOUT = "META-INF/celeste-beta/runtime-layout.json";
    private static final String ASSET = "assets/celeste/test.png";
    private static final String HOST = "dev.c4lam.celeste.beta.resources.MinecraftInternalResourcesHost";
    private static final String RUNTIME = "dev.c4lam.celeste.beta.lifecycle.BetaRuntime";
    private static final String ASSETS = "dev.c4lam.celeste.beta.resources.InternalMemoryResources";
    private static final MemoryPayloadBootstrap.RuntimeLayout MAPPING = new MemoryPayloadBootstrap.RuntimeLayout(
        Set.of("dev.c4lam.celeste."), Set.of("assets/celeste/", "META-INF/celeste-beta/"), ENTRY,
        "startFromRetainedParts", "ready", "stopped", "stop", "status");
    private static Map<String, byte[]> baseline;
    private static int checks;

    private BootstrapBCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected compiled payload fixture directory");
        baseline = new TreeMap<>();
        Path root = Path.of(args[0]);
        try (var paths = Files.walk(root.resolve("dev/c4lam/celeste"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                baseline.put(root.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
            }
        }
        baseline.put(ASSET, new byte[] { 7, 8, 9 });
        baseline.put(LAYOUT, layoutBytes());
        twoLifetimes();
        stopBeforeReady();
        cleanupFailure();
        preflight();
        System.out.println("PASS bootstrap B: " + checks + " checks; signed offline MemoryRelease, real retained digest/loader/session integration");
    }

    private record RetiredLifetime(MemoryPayloadBootstrap.Session session,
                                   java.lang.ref.WeakReference<ClassLoader> loader, int identity) { }

    private static void twoLifetimes() throws Exception {
        RetiredLifetime first = normalStop(), second = normalStop();
        check(first.session() != second.session() && first.identity() != second.identity(),
            "repeated loads create distinct sessions/payload loaders with the same resident parent");
        for (RetiredLifetime lifetime : List.of(first, second)) {
            check(lifetime.session().status().get("phase").equals("stopped"), "old stopped handles remain usable after another lifetime");
            lifetime.session().stop().toCompletableFuture().join();
        }
        // GC scheduling is advisory. Strong-reference retirement above is the gate;
        // record actual collection evidence without promising forced JVM unloading.
        for (int attempt = 0; attempt < 12 && (first.loader().get() != null || second.loader().get() != null); attempt++) {
            System.gc(); Thread.sleep(25);
        }
        int collected = (first.loader().get() == null ? 1 : 0) + (second.loader().get() == null ? 1 : 0);
        System.out.println("Retired payload loader collection observed: " + collected + "/2; stopped B handles deliberately retained; GC is JVM-controlled");
    }

    private static RetiredLifetime normalStop() throws Exception {
        resetGame();
        try (Delivery delivery = new Delivery(baseline)) {
            Prepared prepared = new Prepared();
            MemoryPayloadBootstrap.Session session = MemoryPayloadBootstrap.load(delivery.release, MAPPING, prepared);
            TwoPartMemoryClassLoader loader = loader(session);
            Class<?> resourceHostClass = loader.loadClass(HOST);
            check(prepared.verifications == 1 && prepared.parents == 1, "prepared-game capability checked once before parent selection");
            check(loader.getParent() == prepared.parent && loader.loadClass(ENTRY).getClassLoader() == loader,
                "actual lifecycle entry is child-owned with exact prepared parent");
            check(loader.loadClass(ArtifactIntegrity.class.getName()).getClassLoader() == loader
                && loader.loadClass(MinecraftClient.class.getName()) == MinecraftClient.class,
                "payload integrity implementation is child-owned while Minecraft remains canonical parent-owned");
            check(loader.loadClass(MemoryPayloadBootstrap.class.getName()) == MemoryPayloadBootstrap.class,
                "resident bootstrap is canonical parent-owned");
            check(loader.retainedEntryCount() == baseline.size() && retainedDigest(loader).equals(delivery.digest),
                "both exact retained maps agree with the signed runtime digest");
            check(!done(session.ready()) && session.status().get("phase").equals("preparing"), "load returns lifecycle handle before queued game work");
            ByteBuffer delivered = delivery.release.artifact("payload-a"); delivery.release.close();
            check(allZero(delivered) && !loader.isClosed() && retainedDigest(loader).equals(delivery.digest),
                "closing downloaded release revokes its bytes without corrupting independently retained payload");
            setupResources(loader); pump();
            check((int) value(loader, ASSETS, "lastByte") == 7, "resource activation consumes asset from same verified retained maps");
            check((boolean) value(loader, "dev.c4lam.celeste.auth.AuthBootstrap", "configured"), "payload reaches controlled auth boundary only after retained digest verification");
            check(!done(session.ready()) && !loader.isClosed(), "pending activation retains memory without reporting ready");
            future(loader, HOST, "activation").complete(null); pump();
            Object runtime = value(loader, RUNTIME, "current");
            check(runtime != null && !done(session.ready()), "readiness remains pending until actual payload runtime signals readiness");
            instanceFuture(runtime, "ready").complete(null); pump();
            check(done(session.ready()) && !session.ready().toCompletableFuture().isCompletedExceptionally(), "wrapper observes real payload readiness");
            Map<String, String> snapshot = session.status();
            expect(UnsupportedOperationException.class, () -> snapshot.put("phase", "forged"), "status snapshot is immutable");
            InputStream retainedStream = loader.getResourceAsStream(ASSET);
            check(retainedStream != null && retainedStream.read() == 7, "retained resources readable before stop");
            CompletionStage<Void> stopped = session.stop(); pump();
            check(stopped == session.stopped() && !done(stopped) && !loader.isClosed(), "stop returns shared observation and waits for runtime cleanup");
            instanceFuture(runtime, "stopped").complete(null); pump();
            check((int) value(loader, HOST, "removals") == 1 && !loader.isClosed(), "loader remains usable until replacement reload settles");
            future(loader, HOST, "removal").complete(null); pump();
            stopped.toCompletableFuture().join();
            check(loader.isClosed() && loader.retainedByteCount() == 0 && loader.retainedEntryCount() == 0,
                "successful payload retirement closes and empties retained memory loader");
            expect(IOException.class, retainedStream::read, "existing resource stream revoked after successful stop"); retainedStream.close();
            check(snapshot.get("phase").equals("active") && session.status().get("phase").equals("stopped"), "status is a stable snapshot, with later state independently observable");
            session.stop().toCompletableFuture().join(); pump();
            check(resourceHostClass.getField("closes").getInt(null) == 1, "repeated wrapper stop does not repeat payload cleanup");
            check((int) runtime.getClass().getClassLoader().getParent().loadClass(HOST).getField("closes").get(null) == 0,
                "parent fixture resource host remains unused");
            for (String name : List.of("loader", "runtime", "stop", "status")) {
                Field retired = session.getClass().getDeclaredField(name); retired.setAccessible(true);
                check(retired.get(session) == null, "clean B handle no longer retains payload ownership: " + name);
            }
            return new RetiredLifetime(session, new java.lang.ref.WeakReference<>(loader), System.identityHashCode(loader));
        }
    }

    private static void stopBeforeReady() throws Exception {
        resetGame();
        try (Delivery delivery = new Delivery(baseline)) {
            MemoryPayloadBootstrap.Session session = MemoryPayloadBootstrap.load(delivery.release, MAPPING, new Prepared());
            TwoPartMemoryClassLoader loader = loader(session);
            Class<?> runtime = loader.loadClass(RUNTIME);
            session.stop();
            check(!done(session.stopped()) && !loader.isClosed(), "stop-before-queue-drain does not prematurely revoke memory");
            pump(); session.stopped().toCompletableFuture().join();
            check(session.ready().toCompletableFuture().isCompletedExceptionally() && loader.isClosed(),
                "cancelled real startup never becomes ready and successful retirement closes loader");
            check(runtime.getField("starts").getInt(null) == 0, "stop before queued preparation never starts runtime");
            check(loader(session) == null, "cancelled but fully retired startup also releases its payload loader reference");
        }
    }

    private static void cleanupFailure() throws Exception {
        resetGame();
        try (Delivery delivery = new Delivery(baseline)) {
            MemoryPayloadBootstrap.Session session = MemoryPayloadBootstrap.load(delivery.release, MAPPING, new Prepared());
            TwoPartMemoryClassLoader loader = loader(session);
            setupResources(loader); pump(); future(loader, HOST, "activation").complete(null); pump();
            Object runtime = value(loader, RUNTIME, "current"); instanceFuture(runtime, "ready").complete(null); pump();
            session.stop(); pump(); instanceFuture(runtime, "stopped").complete(null); pump();
            IOException failure = new IOException("controlled replacement reload failure");
            future(loader, HOST, "removal").completeExceptionally(failure); pump();
            check(cause(session.stopped()) == failure && cause(session.stop()) == failure, "original cleanup failure remains exact through wrapper and repeated stop");
            pump();
            check(!loader.isClosed() && loader.retainedByteCount() > 0 && retainedDigest(loader).equals(delivery.digest),
                "failed cleanup deliberately preserves complete verified loader bytes");
            check(loader(session) == loader, "failed cleanup keeps the original loader strongly owned for live resource views");
            check(!(boolean) value(loader, ASSETS, "closed") && (int) value(loader, HOST, "closes") == 0,
                "failed replacement retains resource owner as well as loader");
            try (InputStream resource = loader.getResourceAsStream(ASSET)) { check(resource != null && resource.read() == 7, "resource view remains readable after failed cleanup"); }
            // No game is present: explicitly retire this isolated failed fixture after proving retention.
            loader.close();
        }
    }

    private static void preflight() throws Exception {
        resetGame();
        expect(IllegalArgumentException.class, () -> new MemoryPayloadBootstrap.RuntimeLayout(Set.of("dev.c4lam.celeste."),
            Set.of("assets/celeste/"), ENTRY, "startFromRetainedParts", "ready", "stopped", "stop", "status"), "parent-fallback runtime mapping namespace rejected");
        expect(IllegalArgumentException.class, () -> new MemoryPayloadBootstrap.RuntimeLayout(Set.of("dev.celestebeta."),
            MAPPING.resourcePrefixes(), "dev.celestebeta.Entry", "start", "ready", "stopped", "stop", "status"), "resident namespace ownership rejected");
        expect(IllegalArgumentException.class, () -> new MemoryPayloadBootstrap.RuntimeLayout(Set.of("java.fixture."),
            MAPPING.resourcePrefixes(), "java.fixture.Entry", "start", "ready", "stopped", "stop", "status"), "reserved JDK ownership rejected");
        expect(IllegalArgumentException.class, () -> new MemoryPayloadBootstrap.RuntimeLayout(MAPPING.classPrefixes(),
            MAPPING.resourcePrefixes(), "foreign.Entry", "start", "ready", "stopped", "stop", "status"), "unowned entrypoint rejected");
        expect(IllegalArgumentException.class, () -> new MemoryPayloadBootstrap.RuntimeLayout(MAPPING.classPrefixes(),
            MAPPING.resourcePrefixes(), ENTRY, "bad-name", "ready", "stopped", "stop", "status"), "invalid method mapping rejected");
        try (Delivery delivery = new Delivery(baseline)) {
            Prepared denied = new Prepared(); IOException denial = new IOException("game hooks incomplete"); denied.failure = denial;
            check(expect(IOException.class, () -> MemoryPayloadBootstrap.load(delivery.release, MAPPING, denied), "prepared game rejection") == denial
                && denied.parents == 0 && MinecraftClient.queue.isEmpty(), "game preflight denial precedes parent lookup and any payload action");
            Prepared parentless = new Prepared(); parentless.parent = null;
            expect(NullPointerException.class, () -> MemoryPayloadBootstrap.load(delivery.release, MAPPING, parentless), "null runtime parent rejected");
            byte[] tampered = delivery.delivered.get("payload-a"); tampered[0] ^= 1;
            expect(IOException.class, () -> MemoryPayloadBootstrap.load(delivery.release, MAPPING, new Prepared()), "retained download tampering caught by bootstrap rehash");
            tampered[0] ^= 1; delivery.release.close();
            expect(IllegalStateException.class, () -> MemoryPayloadBootstrap.load(delivery.release, MAPPING, new Prepared()), "closed release rejected");
        }
        try (Delivery expired = new Delivery(baseline, Instant.now().getEpochSecond() - 300)) {
            expect(IOException.class, () -> MemoryPayloadBootstrap.load(expired.release, MAPPING, new Prepared()), "catalog expired between verified download and startup rejected");
        }
        Map<String, byte[]> missing = changed(); missing.remove(LAYOUT);
        ParentMapping parentMapping = new ParentMapping(); Prepared prepared = new Prepared(); prepared.parent = parentMapping;
        rejectedPayload(missing, prepared, IOException.class, "missing retained mapping cannot be supplied by parent");
        check(parentMapping.reads == 0, "owned mapping lookup never asks parent to supply payload metadata");
        for (byte[] invalid : List.of(new byte[0], new byte[65_537], "{}".getBytes(StandardCharsets.UTF_8),
            "not json".getBytes(StandardCharsets.UTF_8))) {
            Map<String, byte[]> payload = changed(); payload.put(LAYOUT, invalid);
            rejectedPayload(payload, new Prepared(), IOException.class, "malformed or bounded mapping rejected");
        }
        JsonObject mismatch = layoutJson(); mismatch.addProperty("ready_method", "differentReady");
        Map<String, byte[]> payload = changed(); payload.put(LAYOUT, mismatch.toString().getBytes(StandardCharsets.UTF_8));
        rejectedPayload(payload, new Prepared(), IOException.class, "signed payload mapping must exactly agree with bootstrap mapping");
        payload = changed(); payload.remove(ENTRY_PATH);
        rejectedPayload(payload, new Prepared(), ClassNotFoundException.class, "missing owned entrypoint cannot fall back to available parent copy");
        for (String method : List.of("ready", "stopped", "stop", "status")) {
            Map<String, byte[]> modified = changed();
            modified.put(ENTRY_PATH, mutateEntry(method, true, false));
            rejectedPayload(modified, new Prepared(), IOException.class, "static lifecycle mapping rejected before startup: " + method);
        }
        payload = changed(); payload.put(ENTRY_PATH, mutateEntry("startFromRetainedParts", false, true));
        rejectedPayload(payload, new Prepared(), IOException.class, "start return must be the exact child entrypoint type");
        payload = changed(); payload.put(ENTRY_PATH, mutateEntry("ready", false, true));
        rejectedPayload(payload, new Prepared(), IOException.class, "readiness return must be a CompletionStage");
        check(MinecraftClient.queue.isEmpty(), "all preflight failures leave actual payload lifecycle unstarted");
    }

    private static void rejectedPayload(Map<String, byte[]> entries, Prepared game, Class<? extends Throwable> type, String label) throws Exception {
        try (Delivery delivery = new Delivery(entries)) {
            expect(type, () -> MemoryPayloadBootstrap.load(delivery.release, MAPPING, game), label);
            check(MinecraftClient.queue.isEmpty(), label + " does not enqueue game work");
        }
    }
    private static byte[] mutateEntry(String name, boolean makeStatic, boolean changeReturn) {
        ClassNode node = new ClassNode(); new ClassReader(baseline.get(ENTRY_PATH)).accept(node, 0);
        var target = node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
        if (makeStatic) {
            target.access |= Opcodes.ACC_STATIC;
            // A valid static sentinel with the original declared return type, never executed by preflight.
            target.instructions.clear(); target.tryCatchBlocks.clear(); target.localVariables = null;
            target.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
            target.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
            target.maxStack = 1; target.maxLocals = 0;
        }
        if (changeReturn) target.desc = target.desc.substring(0, target.desc.indexOf(')') + 1) + "Ljava/lang/Object;";
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    /** Produces a genuine verified release using fresh local fixture signing keys and an in-process transport only. */
    private static final class Delivery implements AutoCloseable {
        final Map<String, byte[]> delivered = new LinkedHashMap<>();
        final BetaDeliveryClient.MemoryRelease release;
        final String digest;
        Delivery(Map<String, byte[]> entries) throws Exception { this(entries, Instant.now().getEpochSecond()); }
        Delivery(Map<String, byte[]> entries, long now) throws Exception {
            Map<String, byte[]> first = new TreeMap<>(), second = new TreeMap<>(); int index = 0;
            for (var entry : new TreeMap<>(entries).entrySet()) (index++ % 2 == 0 ? first : second).put(entry.getKey(), entry.getValue());
            digest = ArtifactIntegrity.digestVerifiedMemory(visitor -> { visitor.accept(first); visitor.accept(second); });
            Map<String, byte[]> artifacts = Map.of("bootstrap-a", new byte[] { 1 }, "bootstrap-b", new byte[] { 2 },
                "payload-a", jar(first), "payload-b", jar(second));
            KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String device = "bootstrap_fixture_device_0000000001";
            JsonObject installer = identity("celeste-installer", device, now, now + 600);
            installer.addProperty("jti", "bootstrap-fixture-grant-0001");
            installer.addProperty("release", "production-launcher"); installer.addProperty("client_sha256", "b".repeat(64));
            installer.addProperty("payload_release", "production-payload"); installer.addProperty("payload_sha256", "c".repeat(64));
            installer.addProperty("bootstrap", "production-bootstrap"); installer.addProperty("bootstrap_sha256", "d".repeat(64));
            String installerToken = sign(keys, installer);
            JsonObject manifest = identity("celeste-beta-manifest", device, now, now + 100);
            manifest.addProperty("installer_jti", "bootstrap-fixture-grant-0001");
            manifest.addProperty("installer_sha256", BetaDeliveryClient.sha256(installerToken.getBytes(StandardCharsets.US_ASCII)));
            manifest.addProperty("schema", "celeste-beta-release-2"); manifest.addProperty("release_id", "offline-bootstrap-b");
            manifest.addProperty("client_version", "fixture-1"); manifest.addProperty("runtime_sha256", digest);
            JsonArray list = new JsonArray();
            for (String id : BetaDeliveryClient.IDS) {
                byte[] bytes = artifacts.get(id); JsonObject artifact = new JsonObject();
                artifact.addProperty("id", id); artifact.addProperty("role", id.startsWith("bootstrap") ? "bootstrap" : "payload");
                artifact.addProperty("url", "/v1/beta/artifacts/offline-bootstrap-b/" + id);
                artifact.addProperty("sha256", BetaDeliveryClient.sha256(bytes)); artifact.addProperty("size", bytes.length); list.add(artifact);
            }
            manifest.add("artifacts", list); JsonObject projection = new JsonObject();
            for (String name : List.of("schema", "release_id", "client_version", "runtime_sha256", "artifacts")) projection.add(name, manifest.get(name));
            manifest.addProperty("catalog_sha256", BetaDeliveryClient.sha256(BetaDeliveryClient.canonical(projection).getBytes(StandardCharsets.UTF_8)));
            String manifestToken = sign(keys, manifest); byte[] publicKey = keys.getPublic().getEncoded();
            BetaDeliveryClient verifier = new BetaDeliveryClient(java.net.URI.create("https://bootstrap-fixture.invalid/"),
                Arrays.copyOfRange(publicKey, publicKey.length - 32, publicKey.length), installerToken, device, 42,
                Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC), (uri, headers, maximum, timeout) -> {
                    String id = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
                    if (!headers.get("Authorization").equals("Bearer " + installerToken)
                        || !headers.get("X-Celeste-Beta-Manifest").equals(manifestToken)) throw new IOException("fixture grant binding failed");
                    byte[] bytes = artifacts.get(id).clone(); delivered.put(id, bytes);
                    return new BetaDeliveryClient.Reply(uri, 200, Map.of("Content-Type", List.of("application/octet-stream")), bytes);
                });
            release = verifier.download(verifier.verifyManifest(manifestToken));
        }
        @Override public void close() { release.close(); }
    }
    private static final class Prepared implements MemoryPayloadBootstrap.PreparedGame {
        ClassLoader parent = BootstrapBCheck.class.getClassLoader();
        int verifications, parents;
        Exception failure;
        public void verifyReadyForPayload() throws Exception { verifications++; if (failure != null) throw failure; }
        public ClassLoader runtimeParent() { parents++; return parent; }
    }
    private static final class ParentMapping extends ClassLoader {
        int reads;
        ParentMapping() { super(BootstrapBCheck.class.getClassLoader()); }
        @Override public InputStream getResourceAsStream(String name) {
            if (name.equals(LAYOUT)) { reads++; return new ByteArrayInputStream(layoutBytes()); }
            return super.getResourceAsStream(name);
        }
    }
    private static JsonObject identity(String audience, String device, long issued, long expires) {
        JsonObject result = new JsonObject(); result.addProperty("aud", audience); result.addProperty("sub", "42"); result.addProperty("product", "celeste_plus");
        result.addProperty("device", device); result.addProperty("iat", issued); result.addProperty("nbf", issued); result.addProperty("exp", expires); return result;
    }
    private static String sign(KeyPair keys, JsonObject claims) throws Exception {
        String header = encode("{\"alg\":\"EdDSA\",\"kid\":\"celeste-release-1\",\"typ\":\"CST\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encode(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(keys.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII)); return header + "." + payload + "." + encode(signer.sign());
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                JarEntry item = new JarEntry(entry.getKey()); item.setTime(0); output.putNextEntry(item); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static JsonObject layoutJson() {
        JsonObject json = new JsonObject(); json.addProperty("schema", "celeste-beta-runtime-layout-1");
        JsonArray classes = new JsonArray(); MAPPING.classPrefixes().stream().sorted().forEach(classes::add); json.add("class_prefixes", classes);
        JsonArray resources = new JsonArray(); MAPPING.resourcePrefixes().stream().sorted().forEach(resources::add); json.add("resource_prefixes", resources);
        json.addProperty("entry_class", ENTRY); json.addProperty("start_method", MAPPING.startMethod());
        json.addProperty("ready_method", "ready"); json.addProperty("stopped_method", "stopped");
        json.addProperty("stop_method", "stop"); json.addProperty("status_method", "status"); return json;
    }
    private static byte[] layoutBytes() { return layoutJson().toString().getBytes(StandardCharsets.UTF_8); }
    private static Map<String, byte[]> changed() { return new TreeMap<>(baseline); }
    private static TwoPartMemoryClassLoader loader(MemoryPayloadBootstrap.Session session) throws ReflectiveOperationException {
        Field field = session.getClass().getDeclaredField("loader"); field.setAccessible(true); return (TwoPartMemoryClassLoader) field.get(session);
    }
    private static String retainedDigest(TwoPartMemoryClassLoader loader) throws IOException { return ArtifactIntegrity.digestVerifiedMemory(visitor -> loader.visitRetainedParts(visitor::accept)); }
    private static Object value(ClassLoader loader, String type, String name) throws ReflectiveOperationException { return loader.loadClass(type).getField(name).get(null); }
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> future(ClassLoader loader, String type, String name) throws ReflectiveOperationException {
        return (CompletableFuture<Void>) value(loader, type, name);
    }
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> instanceFuture(Object instance, String name) throws ReflectiveOperationException {
        return (CompletableFuture<Void>) instance.getClass().getField(name).get(instance);
    }
    private static void setupResources(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> host = loader.loadClass(HOST);
        host.getField("activation").set(null, new CompletableFuture<Void>());
        host.getField("removal").set(null, new CompletableFuture<Void>());
    }
    private static void resetGame() { MinecraftClient.queue.clear(); MinecraftClient.reject = false; }
    private static void pump() {
        int budget = 1000;
        while (!MinecraftClient.queue.isEmpty()) {
            if (--budget == 0) throw new AssertionError("runaway client scheduling"); MinecraftClient.queue.removeFirst().run();
        }
    }
    private static boolean allZero(ByteBuffer buffer) { while (buffer.hasRemaining()) if (buffer.get() != 0) return false; return true; }
    private static boolean done(CompletionStage<Void> stage) { return stage.toCompletableFuture().isDone(); }
    private static Throwable cause(CompletionStage<Void> stage) {
        try { stage.toCompletableFuture().join(); }
        catch (CompletionException failure) { return failure.getCause(); }
        throw new AssertionError("expected lifecycle failure");
    }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String label) {
        checks++; try { action.run(); }
        catch (Throwable failure) {
            if (failure instanceof InvocationTargetException wrapper) failure = wrapper.getCause();
            if (type.isInstance(failure)) return failure; throw new AssertionError(label, failure);
        }
        throw new AssertionError(label + ": no exception");
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
