package dev.celestebeta.bootstrap.external;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.c4lam.celeste.beta.memory.TwoPartMemoryClassLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
/** Offline archive/support and Instrumentation-proxy transaction checks. Never attaches to or transforms a JVM. */
public final class BootstrapACheck {
    private static final String INTERNAL = "dev.celestebeta.bootstrap.internal.MemoryPayloadBootstrap";
    private static final String BRIDGE = "dev/celestebeta/host/ResourceReloadBridge.class";
    private static int checks;
    private static java.util.jar.JarFile minecraftSchemas;
    private BootstrapACheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--minecraft-schema")) {
            try (var schemas = new java.util.jar.JarFile(Path.of(args[1]).toFile())) {
                minecraftSchemas = schemas;
                hooks();
                hostLifetimes();
            } finally { minecraftSchemas = null; }
            System.out.println("PASS actual Minecraft schema gate: " + checks + " checks; hook transactions and host cleanup against supplied class bytes; no Minecraft launch");
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("Expected isolated fixture output directory");
        plusProtocolBoundary();
        dev.c4lam.celeste.beta.delivery.RuntimeInstallationSignerCheck.run();
        dev.c4lam.celeste.auth.PortableCredentialProtectionCheck.run();
        interactionApiPresence();
        classIdentity();
        archive();
        cachedArchiveResources();
        Path root = Path.of(args[0]).toAbsolutePath().normalize(); Files.createDirectories(root);
        Path temporary = Files.createTempDirectory(root, "bootstrap-a-check-").toRealPath();
        try { support(temporary); supportPreparationCleanup(temporary); dev.celestebeta.attach.BootstrapIpcCheck.run(temporary); }
        finally { eraseFixture(temporary); }
        hooks();
        hostLifetimes();
        System.out.println("PASS bootstrap A: " + checks + " checks; verified B archive, retained support ownership and simulated hook transactions");
        System.out.println("Actual Minecraft schemas not tested by this task; run betaMinecraftSchemaCheck with -PminecraftSchemaJar for that separate gate.");
    }

    private static void plusProtocolBoundary() throws Exception {
        var uid = BootstrapHost.class.getDeclaredMethod("requestUid", JsonObject.class); uid.setAccessible(true);
        for (String accepted : List.of("1", "7", "42", Long.toString(Long.MAX_VALUE))) {
            JsonObject value = com.google.gson.JsonParser.parseString("{\"uid\":" + accepted + "}").getAsJsonObject();
            check(((Long) reflected(uid, null, value)) == Long.parseLong(accepted), "positive canonical UID accepted: " + accepted);
        }
        for (String rejected : List.of("0", "-1", "42.0", "42.5", "4.2e1", "9223372036854775808", "18446744073709551616",
                "\"42\"", "\"042\"", "\"４２\"", "true", "null", "{}", "[]")) {
            JsonObject value = com.google.gson.JsonParser.parseString("{\"uid\":" + rejected + "}").getAsJsonObject();
            expect(IOException.class, () -> reflected(uid, null, value), "noncanonical or out-of-range UID rejected: " + rejected);
        }
        expect(IOException.class, () -> reflected(uid, null, new JsonObject()), "missing UID rejected");
        // Real command rejects a malformed UID before touching instrumentation, paths, or network.
        JsonObject command = new JsonObject(); command.addProperty("action", "start"); command.addProperty("uid", "42");
        for (String key : List.of("installer", "device", "support_directory", "expected_game_directory", "expected_release", "expected_catalog_sha256")) command.addProperty(key, "unused");
        expect(IOException.class, () -> BootstrapHost.command(null, command.toString(), "unused"), "start uses strict UID parser before side effects");

        var validate = BootstrapHost.class.getDeclaredMethod("validatedTrust", JsonObject.class); validate.setAccessible(true);
        JsonObject trust = new JsonObject(); trust.addProperty("schema", "celeste-plus-bootstrap-trust-1");
        trust.addProperty("product", "celeste_plus"); trust.addProperty("origin", "https://offline.invalid/");
        trust.addProperty("server_key", "A".repeat(43));
        check(reflected(validate, null, trust) == trust, "Celeste+ trust profile accepted without identity-specific policy");
        for (String product : List.of("celeste", "both", "", "7")) {
            JsonObject changed = trust.deepCopy(); changed.addProperty("product", product);
            expect(IOException.class, () -> reflected(validate, null, changed), "cross-product trust rejected: " + product);
        }
        for (String key : trust.keySet()) {
            JsonObject missing = trust.deepCopy(); missing.remove(key);
            expect(IOException.class, () -> reflected(validate, null, missing), "missing trust field rejected: " + key);
            JsonObject number = trust.deepCopy(); number.addProperty(key, 42);
            expect(IOException.class, () -> reflected(validate, null, number), "nonstring trust field rejected: " + key);
            JsonObject nullValue = trust.deepCopy(); nullValue.add(key, com.google.gson.JsonNull.INSTANCE);
            expect(IOException.class, () -> reflected(validate, null, nullValue), "null trust field rejected: " + key);
        }
        JsonObject old = trust.deepCopy(); old.addProperty("schema", "celeste-beta-bootstrap-trust-1");
        expect(IOException.class, () -> reflected(validate, null, old), "legacy Owner trust cannot substitute for product-scoped trust");
        JsonObject extra = trust.deepCopy(); extra.addProperty("owner_uid", 7);
        expect(IOException.class, () -> reflected(validate, null, extra), "unexpected Owner shortcut rejected");
    }

    private static void interactionApiPresence() throws Exception {
        for (String metadataVersion : Arrays.asList("4.0.44+1fb1cde93e", "99.0.0+future-build", null)) {
            MockFabricLoader.reset(java.util.Optional.of(new LoadedInteractionFixture(metadataVersion)));
            MinecraftTarget.requireInteractionApi(MockFabricLoader.class);
            check("fabric-events-interaction-v0".equals(MockFabricLoader.requestedId),
                "loaded interaction component is queried by its exact Fabric ID");
            check(MockFabricLoader.requests == 1, "loaded component accepted without consulting metadata or version");
        }
        for (Object result : Arrays.asList(java.util.Optional.empty(), "malformed container result", null)) {
            MockFabricLoader.reset(result);
            expect(IOException.class, () -> MinecraftTarget.requireInteractionApi(MockFabricLoader.class),
                "absent or malformed loaded interaction component rejected");
            check("fabric-events-interaction-v0".equals(MockFabricLoader.requestedId),
                "failed presence lookup still requests only the interaction component");
        }
    }

    /** Public declaring class and methods exercise the same reflection boundary as Fabric Loader. */
    public static final class MockFabricLoader {
        private static final MockFabricLoader INSTANCE = new MockFabricLoader();
        private static Object result;
        private static String requestedId;
        private static int requests;
        private MockFabricLoader() { }
        private static void reset(Object response) { result = response; requestedId = null; requests = 0; }
        public static MockFabricLoader getInstance() { return INSTANCE; }
        public Object getModContainer(String id) { requestedId = id; requests++; return result; }
    }

    public static final class LoadedInteractionFixture {
        private final String metadataVersion;
        private LoadedInteractionFixture(String metadataVersion) { this.metadataVersion = metadataVersion; }
        public Object getMetadata() {
            throw new AssertionError("Presence must not read component metadata, including version " + metadataVersion);
        }
    }

    private static void archive() throws Exception {
        Map<String, byte[]> valid = archiveEntries(); byte[] jar = jar(valid, true);
        ClassLoader parent = new ClassLoader(BootstrapACheck.class.getClassLoader()) {
            @Override public InputStream getResourceAsStream(String name) {
                if (name.equals(VerifiedBootstrapLoader.LAYOUT)) return new ByteArrayInputStream(new byte[] { 99 });
                return super.getResourceAsStream(name);
            }
        };
        VerifiedBootstrapLoader loader = new VerifiedBootstrapLoader(jar, VerifiedBootstrapLoader.hash(jar), parent);
        byte[] snapshot = jar.clone();
        check(loader.loadClass(INTERNAL).getClassLoader() == loader, "signed internal bootstrap class is child-owned");
        check(loader.loadClass(String.class.getName()) == String.class, "JDK remains parent-owned");
        check(Arrays.equals(loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT).readAllBytes(), valid.get(VerifiedBootstrapLoader.LAYOUT)),
            "retained B mapping overrides parent mapping");
        expect(ClassNotFoundException.class, () -> loader.loadClass(VerifiedBootstrapLoader.PREFIX + "Missing"), "owned missing B class never delegates");
        loader.close(); loader.close();
        check(loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT) == null && Arrays.equals(snapshot, jar), "close revokes B lookups without mutating caller archive");
        expect(ClassNotFoundException.class, () -> loader.loadClass(INTERNAL), "closed B loader rejects already-defined owned classes");
        expect(IOException.class, () -> new VerifiedBootstrapLoader(jar, "0".repeat(64), parent), "incorrect trusted B hash rejected");
        rejectArchive(new byte[0], "empty archive rejected"); rejectArchive(new byte[8 * 1024 * 1024 + 1], "compressed archive bound enforced");
        for (String missing : List.of("META-INF/MANIFEST.MF", VerifiedBootstrapLoader.LAYOUT, INTERNAL.replace('.', '/') + ".class")) {
            Map<String, byte[]> changed = archiveEntries(); changed.remove(missing); rejectArchive(jar(changed, false), "required B artifact member missing: " + missing);
        }
        Map<String, byte[]> changed = archiveEntries(); changed.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\nCeleste-Beta-Bootstrap: A\r\n\r\n"));
        rejectArchive(jar(changed, false), "bootstrap A cannot substitute for bootstrap B role");
        for (String forbidden : List.of("other/Payload.class", "../escape.class", "./relative.class", "/absolute.class", "x//duplicate.class")) {
            changed = archiveEntries(); changed.put(forbidden, new byte[] { 1 }); rejectArchive(jar(changed, false), "unexpected/path-traversing B member rejected");
        }
        changed = archiveEntries(); changed.put("directory/", new byte[] { 1 }); rejectArchive(jar(changed, false), "directory payload bytes rejected");
        changed = archiveEntries(); changed.put(VerifiedBootstrapLoader.LAYOUT, new byte[2 * 1024 * 1024 + 1]);
        rejectArchive(jar(changed, false), "per-entry expansion bound enforced");
        changed = archiveEntries();
        for (int i = 0; i < 9; i++) changed.put(VerifiedBootstrapLoader.PREFIX.replace('.', '/') + "Large" + i + ".class", new byte[2 * 1024 * 1024]);
        rejectArchive(jar(changed, false), "aggregate expansion bound enforced");
        changed = archiveEntries(); for (int i = 0; i < 513; i++) changed.put("dir" + i + "/", new byte[0]);
        rejectArchive(jar(changed, false), "directory entries count toward member limit");
        byte[] corrupt = jar.clone(); int location = indexOf(corrupt, valid.get(VerifiedBootstrapLoader.LAYOUT)); corrupt[location] ^= 1;
        rejectArchive(corrupt, "stored member CRC checked even when outer archive hash matches");
        centralDirectory(jar);
    }

    private static void cachedArchiveResources() throws Exception {
        byte[] jar = jar(archiveEntries(), true);
        List<RetiredArchiveResources> retired = new ArrayList<>();
        for (int lifetime = 0; lifetime < 3; lifetime++) retired.add(retireArchiveResources(jar));
        // Only tests request GC: production releases ownership and lets the JVM decide.
        for (int attempt = 0; attempt < 40 && retired.stream().anyMatch(item -> item.loader().get() != null || item.bytes().get() != null); attempt++) {
            System.gc(); Thread.sleep(25);
        }
        for (RetiredArchiveResources item : retired) {
            check(item.loader().get() == null, "cached URL/connection/streams do not pin a retired B loader");
            check(item.bytes().get() == null, "cached resource handles release retired B resource arrays");
            check(item.handles().size() == 6, "all stale resource handles remain retained during collection check");
        }
        java.lang.ref.Reference.reachabilityFence(retired);
    }

    @SuppressWarnings("deprecation") // Context-relative URLs deliberately exercise the installed handler.
    private static RetiredArchiveResources retireArchiveResources(byte[] jar) throws Exception {
        ClassLoader parent = new ClassLoader(BootstrapACheck.class.getClassLoader()) {
            @Override public URL getResource(String name) {
                return name.equals(VerifiedBootstrapLoader.LAYOUT) ? BootstrapACheck.class.getResource("BootstrapACheck.class") : super.getResource(name);
            }
        };
        VerifiedBootstrapLoader loader = new VerifiedBootstrapLoader(jar, VerifiedBootstrapLoader.hash(jar), parent);
        loader.loadClass(INTERNAL);
        URL resource = loader.getResource(VerifiedBootstrapLoader.LAYOUT);
        check(resource != null, "owned B resource URL available before retirement");
        var connected = resource.openConnection(); connected.connect();
        var pending = resource.openConnection();
        InputStream urlStream = connected.getInputStream();
        InputStream directStream = loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT);
        InputStream closedStream = loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT);
        check(directStream != null && closedStream != null, "direct B streams available before retirement");
        int first = directStream.read(); directStream.mark(0); int second = directStream.read(); directStream.reset();
        check(first >= 0 && second == directStream.read() && directStream.markSupported(), "resource stream keeps byte/mark/reset semantics");
        check(directStream.skip(-1) == 0 && directStream.skip(Long.MAX_VALUE) > 0 && directStream.read() == -1,
            "resource stream skip is bounded and EOF remains stable");
        closedStream.close();
        expect(IOException.class, closedStream::read, "explicitly closed resource stream releases access");
        expect(IOException.class, () -> new URL(resource, "?other").openConnection(), "resource URL query tampering rejected");
        expect(IOException.class, () -> new URL(resource, "#other").openConnection(), "resource URL fragment tampering rejected");
        expect(IOException.class, () -> new URL(resource, "//elsewhere" + resource.getPath()).openConnection(), "resource URL authority tampering rejected");
        var field = VerifiedBootstrapLoader.class.getDeclaredField("entries"); field.setAccessible(true);
        Object retainedBytes = ((Map<?, ?>) field.get(loader)).get(VerifiedBootstrapLoader.LAYOUT);
        var bytes = new java.lang.ref.WeakReference<>(retainedBytes);
        var reference = new java.lang.ref.WeakReference<ClassLoader>(loader);
        loader.close();
        check(loader.getResource(VerifiedBootstrapLoader.LAYOUT) == null && !loader.getResources(VerifiedBootstrapLoader.LAYOUT).hasMoreElements(),
            "closed owned URL and enumeration never fall back to parent metadata");
        expect(IOException.class, resource::openStream, "cached B URL revoked after close");
        expect(IOException.class, connected::getInputStream, "already connected B URL revoked after close");
        expect(IOException.class, pending::connect, "pending B connection revoked after close");
        expect(IOException.class, urlStream::read, "open URL stream revoked after close");
        expect(IOException.class, directStream::available, "open direct stream revoked after close");
        expect(IOException.class, directStream::reset, "reset cannot revive a retired resource stream");
        return new RetiredArchiveResources(reference, bytes, List.of(resource, connected, pending, urlStream, directStream, closedStream));
    }

    private record RetiredArchiveResources(java.lang.ref.WeakReference<ClassLoader> loader,
                                            java.lang.ref.WeakReference<Object> bytes, List<Object> handles) { }

    private static void centralDirectory(byte[] original) throws Exception {
        int end = original.length - 22;
        int central = little(original).getInt(end + 16);
        rejectArchive(Arrays.copyOf(original, end), "missing ZIP end record rejected");
        rejectArchive(Arrays.copyOf(original, central), "complete local members without central directory rejected");
        rejectArchive(Arrays.copyOf(original, original.length + 1), "unannounced trailing archive bytes rejected");
        for (String mode : List.of("disk", "count", "central-size", "central-offset", "member-size", "compressed-size", "crc", "method", "member-disk", "flags", "name-length", "local-offset")) {
            byte[] changed = original.clone(); var bytes = little(changed);
            switch (mode) {
                case "disk" -> bytes.putShort(end + 4, (short) 1);
                case "count" -> { short count = (short) (bytes.getShort(end + 10) + 1); bytes.putShort(end + 8, count); bytes.putShort(end + 10, count); }
                case "central-size" -> bytes.putInt(end + 12, bytes.getInt(end + 12) + 1);
                case "central-offset" -> bytes.putInt(end + 16, central + 1);
                case "member-size" -> bytes.putInt(central + 24, bytes.getInt(central + 24) + 1);
                case "compressed-size" -> bytes.putInt(central + 20, bytes.getInt(central + 20) + 1);
                case "crc" -> bytes.putInt(central + 16, bytes.getInt(central + 16) ^ 1);
                case "method" -> bytes.putShort(central + 10, (short) 8);
                case "member-disk" -> bytes.putShort(central + 34, (short) 1);
                case "flags" -> bytes.putShort(central + 8, (short) (bytes.getShort(central + 8) | 1));
                case "name-length" -> bytes.putShort(central + 28, (short) 65535);
                case "local-offset" -> {
                    int second = central + 46 + Short.toUnsignedInt(bytes.getShort(central + 28))
                        + Short.toUnsignedInt(bytes.getShort(central + 30)) + Short.toUnsignedInt(bytes.getShort(central + 32));
                    bytes.putInt(second + 42, bytes.getInt(central + 42));
                }
                default -> throw new AssertionError(mode);
            }
            rejectArchive(changed, "central/local agreement enforced with matching outer hash: " + mode);
        }
        byte[] commented = Arrays.copyOf(original, original.length + 3); little(commented).putShort(end + 20, (short) 3);
        System.arraycopy(bytes("end"), 0, commented, original.length, 3);
        try (VerifiedBootstrapLoader loader = new VerifiedBootstrapLoader(commented, VerifiedBootstrapLoader.hash(commented), BootstrapACheck.class.getClassLoader())) {
            check(loader.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT) != null, "ordinary bounded ZIP comment remains supported");
        }
    }

    private static java.nio.ByteBuffer little(byte[] bytes) { return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN); }

    private static void support(Path root) throws Exception {
        SupportFixture fixture = new SupportFixture();
        try (TwoPartMemoryClassLoader retained = fixture.retained()) {
            ResidentSupport prepared = ResidentSupport.prepare(retained, root, fixture.agent());
            check(prepared.directory().toRealPath().startsWith(root), "support materialization stays inside isolated fixture root");
            for (var entry : fixture.jars.entrySet()) {
                check(Arrays.equals(Files.readAllBytes(prepared.directory().resolve(entry.getKey())), entry.getValue()), "materialized support is byte-exact: " + entry.getKey());
            }
            expect(NullPointerException.class, prepared::loader, "support cannot expose a loader before installation");
            // Inspect the existing private loader in isolation; never append any test jar to the real bootstrap search path.
            Class<?> loaderType = Arrays.stream(ResidentSupport.class.getDeclaredClasses()).filter(type -> type.getSimpleName().equals("SupportLoader")).findFirst().orElseThrow();
            var constructor = loaderType.getDeclaredConstructor(URL[].class, ClassLoader.class); constructor.setAccessible(true);
            List<URL> urls = new ArrayList<>();
            for (String name : fixture.jars.keySet()) if (!name.equals("celeste-beta-bridges.jar")) urls.add(prepared.directory().resolve(name).toUri().toURL());
            ClassLoader parent = new ClassLoader(BootstrapACheck.class.getClassLoader()) {
                @Override public URL getResource(String name) { return name.startsWith("com/sun/jna/") ? urls.getFirst() : super.getResource(name); }
            };
            try (URLClassLoader loader = (URLClassLoader) constructor.newInstance(urls.toArray(URL[]::new), parent)) {
                check(loader.loadClass("com.sun.jna.Native").getClassLoader() == loader
                    && loader.loadClass("oshi.SystemInfo").getClassLoader() == loader, "signed support owns JNA and OSHI classes");
                check(loader.loadClass(String.class.getName()) == String.class, "support keeps JDK identity parent-owned");
                check(loader.getResource("com/sun/jna/probe.txt") != null && loader.getResource("com/sun/jna/missing.txt") == null,
                    "owned resident resources resolve signed bytes without parent fallback");
                check(!loader.getResources("com/sun/jna/missing.txt").hasMoreElements(), "owned resource enumeration also excludes parent");
                expect(ClassNotFoundException.class, () -> loader.loadClass("com.sun.jna.Missing"), "owned resident class cannot fall back");
                // Model an already completed installation without appending fixtures to this JVM.
                field(ResidentSupport.class, "loader").set(prepared, loader);
                field(ResidentSupport.class, "installed").setBoolean(prepared, true);
                long count = childCount(root);
                check(ResidentSupport.prepare(retained, root, fixture.agent(), prepared) == prepared
                    && prepared.loader() == loader && childCount(root) == count,
                    "next verified lifetime reuses exact support owner/loader without writing another native directory");
                expect(IOException.class, () -> prepared.install(null, new DummyLoader(), parent),
                    "another Minecraft loader cannot reuse resident JNI support");
                SupportFixture different = new SupportFixture();
                different.jars.put("media.jar", jar(Map.of("fixture.txt", bytes("changed support")), false)); different.descriptors();
                try (TwoPartMemoryClassLoader changed = different.retained()) {
                    expect(IOException.class, () -> ResidentSupport.prepare(changed, root, different.agent(), prepared),
                        "different fully verified support bytes still require a new JVM");
                }
                Path media = prepared.directory().resolve("media.jar"); byte[] originalMedia = Files.readAllBytes(media);
                Files.write(media, new byte[] { 1 });
                expect(IOException.class, () -> ResidentSupport.prepare(retained, root, fixture.agent(), prepared),
                    "tampered materialized resident bytes are rejected before reuse");
                Files.write(media, originalMedia);
                field(ResidentSupport.class, "installed").setBoolean(prepared, false);
                field(ResidentSupport.class, "loader").set(prepared, null);
                expect(IOException.class, () -> ResidentSupport.prepare(retained, root, fixture.agent(), prepared),
                    "an incomplete support installation cannot be reused");
            }
            var loadedBridge = new DummyLoader().define(BRIDGE.substring(0, BRIDGE.length() - 6).replace('/', '.'), tinyClass(BRIDGE.substring(0, BRIDGE.length() - 6)));
            Instrumentation already = (Instrumentation) Proxy.newProxyInstance(BootstrapACheck.class.getClassLoader(), new Class<?>[] { Instrumentation.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getAllLoadedClasses")) return new Class<?>[] { loadedBridge };
                    throw new AssertionError("install should reject existing bridge before " + method.getName());
                });
            expect(IOException.class, () -> prepared.install(already, parent, parent), "existing canonical bridge rejected before irreversible bootstrap append");
        }
        for (String mode : List.of("agent-mismatch", "retained-hash", "wrong-owner", "wrong-parent", "bad-bridge",
            "size-overflow", "size-fraction", "size-string", "size-negative", "size-exponent", "required-string", "nested-string")) {
            SupportFixture invalid = new SupportFixture();
            switch (mode) {
                case "agent-mismatch" -> invalid.agentMismatch = true;
                case "retained-hash" -> invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject().addProperty("sha256", "0".repeat(64));
                case "wrong-owner" -> invalid.contract.getAsJsonArray("resident_owned_prefixes").add("net.minecraft.");
                case "wrong-parent" -> invalid.contract.addProperty("parent", "arbitrary parent loader");
                case "bad-bridge" -> invalid.replaceBridge(jar(Map.of("foreign/Injected.class", tinyClass("foreign/Injected")), false));
                case "size-overflow" -> {
                    JsonObject item = invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject();
                    item.addProperty("size", item.get("size").getAsLong() + (1L << 32));
                }
                case "size-fraction" -> {
                    JsonObject item = invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject();
                    item.addProperty("size", item.get("size").getAsDouble() + 0.5);
                }
                case "size-string" -> {
                    JsonObject item = invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject();
                    item.addProperty("size", item.get("size").getAsString());
                }
                case "size-negative" -> invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject().addProperty("size", -1);
                case "size-exponent" -> {
                    JsonObject item = invalid.contract.getAsJsonArray("libraries").get(0).getAsJsonObject();
                    item.add("size", com.google.gson.JsonParser.parseString("1e100"));
                }
                case "required-string" -> invalid.contract.addProperty("must_validate_from_retained_payload", "true");
                case "nested-string" -> invalid.contract.addProperty("nested_jars_are_not_automatically_classpath_entries", "true");
                default -> throw new AssertionError(mode);
            }
            long before = childCount(root);
            try (TwoPartMemoryClassLoader retained = invalid.retained()) {
                expect(IOException.class, () -> ResidentSupport.prepare(retained, root, invalid.agent()), "invalid resident support rejected: " + mode);
            }
            check(childCount(root) == before, "rejected resident support leaves no materialized directory: " + mode);
        }
    }

    private static void supportPreparationCleanup(Path root) throws Exception {
        long baseline = childCount(root);
        for (int attempt = 0; attempt < 3; attempt++) {
            SupportFixture fixture = new SupportFixture();
            try (TwoPartMemoryClassLoader retained = fixture.retained()) {
                ResidentSupport prepared = ResidentSupport.prepare(retained, root, fixture.agent());
                prepared.discardIfUninstalled(); prepared.discardIfUninstalled();
                check(!Files.exists(prepared.directory()) && childCount(root) == baseline,
                    "abandoned pre-install support leaves no directory after repeated/idempotent cleanup");
            }
        }
        SupportFixture fixture = new SupportFixture();
        try (TwoPartMemoryClassLoader retained = fixture.retained()) {
            ResidentSupport prepared = ResidentSupport.prepare(retained, root, fixture.agent());
            Path foreign = prepared.directory().resolve("unowned.txt"); Files.writeString(foreign, "keep");
            IOException original = new IOException("original preparation failure");
            var rollback = BootstrapHost.class.getDeclaredMethod("rollbackPreparation", HookInstallation.class, VerifiedBootstrapLoader.class, ResidentSupport.class, Throwable.class);
            rollback.setAccessible(true);
            reflected(rollback, null, null, null, prepared, original);
            check(original.getSuppressed().length == 1 && original.getMessage().equals("original preparation failure"),
                "rollback preserves original failure and records incomplete support cleanup");
            check(Files.readString(foreign).equals("keep") && childCount(prepared.directory()) == 7,
                "unowned child refuses preparation cleanup before any deletion");
            Files.delete(foreign);
            try (java.util.jar.JarFile bridge = new java.util.jar.JarFile(prepared.directory().resolve("celeste-beta-bridges.jar").toFile())) {
                field(ResidentSupport.class, "retainedBridge").set(prepared, bridge);
                prepared.discardIfUninstalled();
                check(childCount(prepared.directory()) == 6, "partial irreversible bridge installation preserves support files");
                field(ResidentSupport.class, "retainedBridge").set(prepared, null);
            }
            field(ResidentSupport.class, "installed").setBoolean(prepared, true);
            prepared.discardIfUninstalled();
            check(childCount(prepared.directory()) == 6, "installed resident support is never discarded by preparation rollback");
            field(ResidentSupport.class, "installed").setBoolean(prepared, false);
            reflected(rollback, null, null, null, prepared, new IOException("reversible hook preparation failure"));
            check(!Files.exists(prepared.directory()) && childCount(root) == baseline,
                "host reversible rollback discards only its uninstalled prepared support");
        }
        // Exercise partial materialization directly without adding a production fault seam.
        Class<?> type = Arrays.stream(ResidentSupport.class.getDeclaredClasses())
            .filter(value -> value.getSimpleName().equals("PreparedDirectory")).findFirst().orElseThrow();
        var constructor = type.getDeclaredConstructor(Path.class); constructor.setAccessible(true);
        Object partial = constructor.newInstance(root);
        var write = type.getDeclaredMethod("write", String.class, byte[].class); write.setAccessible(true);
        var discard = type.getDeclaredMethod("discard"); discard.setAccessible(true);
        Path first = (Path) reflected(write, partial, "first.jar", new byte[] { 1 });
        expect(java.nio.file.FileAlreadyExistsException.class, () -> reflected(write, partial, "first.jar", new byte[] { 2 }),
            "preparation cannot overwrite even its existing file");
        reflected(discard, partial);
        check(!Files.exists(first.getParent()) && childCount(root) == baseline, "partial written support directory cleanup removes only recorded files");
        Object changed = constructor.newInstance(root);
        Path replaced = (Path) reflected(write, changed, "first.jar", new byte[] { 1 });
        Files.delete(replaced); Files.createDirectory(replaced);
        expect(IOException.class, () -> reflected(discard, changed), "changed file type is preserved without recursive cleanup");
        check(Files.isDirectory(replaced), "replacement directory remains untouched");
        Files.delete(replaced);
        reflected(discard, changed);
        check(childCount(root) == baseline, "safe cleanup can finish once the unowned replacement is removed");
        Object modified = constructor.newInstance(root);
        Path altered = (Path) reflected(write, modified, "first.jar", new byte[] { 1 });
        Files.write(altered, new byte[] { 2 });
        expect(IOException.class, () -> reflected(discard, modified), "content receipt preserves a modified owned path");
        check(Files.readAllBytes(altered)[0] == 2, "modified content is never deleted by optional preparation cleanup");
        Files.write(altered, new byte[] { 1 });
        reflected(discard, modified);
        check(childCount(root) == baseline, "verified original content can be safely retired");
    }

    private static void classIdentity() throws Exception {
        byte[] original = identityFixture(false);
        String expected = ClassFileIdentity.canonicalHash(original);
        byte[] reserialized = reserialize(original);
        check(!Arrays.equals(original, reserialized), "constant-pool and declaration-order fixture actually changes bytes");
        check(expected.equals(ClassFileIdentity.canonicalHash(reserialized)), "constant-pool rebuilding and method reordering preserve identity");
        check(expected.equals(ClassFileIdentity.canonicalHash(identityFixture(true))), "equivalent expanded/full frame encoding preserves identity");
        for (String mutation : List.of("opcode", "constant", "annotation", "schema", "frame", "parameter", "debug")) {
            ClassNode changed = identityNode(original);
            MethodNode choose = changed.methods.stream().filter(method -> method.name.equals("choose")).findFirst().orElseThrow();
            switch (mutation) {
                case "opcode" -> {
                    for (var instruction : choose.instructions.toArray()) if (instruction.getOpcode() == Opcodes.ICONST_1) {
                        choose.instructions.set(instruction, new InsnNode(Opcodes.ICONST_3)); break;
                    }
                }
                case "constant" -> {
                    for (MethodNode method : changed.methods) for (var instruction : method.instructions) {
                        if (instruction instanceof LdcInsnNode constant) constant.cst = "modified behavior";
                    }
                }
                case "annotation" -> changed.visibleAnnotations.getFirst().values.set(1, "modified provenance");
                case "schema" -> changed.fields.getFirst().desc = "J";
                case "frame" -> {
                    for (var instruction : choose.instructions) if (instruction instanceof FrameNode frame) frame.local.set(0, Opcodes.FLOAT);
                }
                case "parameter" -> choose.parameters.getFirst().name = "changedArgument";
                case "debug" -> changed.sourceFile = "Changed.java";
                default -> throw new AssertionError(mutation);
            }
            check(!expected.equals(ClassFileIdentity.canonicalHash(identityBytes(changed))), "canonical identity preserves meaningful/retained change: " + mutation);
        }
        for (String scope : List.of("class", "field", "method", "record")) {
            ClassNode opaque = identityNode(original);
            List<org.objectweb.asm.Attribute> attributes = new ArrayList<>(List.of(new OpaqueFixtureAttribute()));
            switch (scope) {
                case "class" -> opaque.attrs = attributes;
                case "field" -> opaque.fields.getFirst().attrs = attributes;
                case "method" -> opaque.methods.getFirst().attrs = attributes;
                case "record" -> {
                    var component = new org.objectweb.asm.tree.RecordComponentNode("value", "I", null);
                    component.attrs = attributes; opaque.recordComponents = new ArrayList<>(List.of(component));
                }
                default -> throw new AssertionError(scope);
            }
            expect(IOException.class, () -> ClassFileIdentity.canonicalHash(identityBytes(opaque)), "opaque attributes fail closed: " + scope);
        }
        ClassNode duplicateMethod = identityNode(original); duplicateMethod.methods.add(duplicateMethod.methods.getFirst());
        expect(IOException.class, () -> ClassFileIdentity.canonicalHash(identityBytes(duplicateMethod)), "duplicate methods rejected");
        ClassNode duplicateField = identityNode(original); duplicateField.fields.add(duplicateField.fields.getFirst());
        expect(IOException.class, () -> ClassFileIdentity.canonicalHash(identityBytes(duplicateField)), "duplicate fields rejected");
        expect(IOException.class, () -> ClassFileIdentity.canonicalHash(new byte[] {0, 1, 2}), "malformed class rejected");
    }
    private static byte[] identityFixture(boolean fullFrame) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Identity", null, "java/lang/Object", null);
        writer.visitSource("Identity.java", null);
        var annotation = writer.visitAnnotation("Lfixture/IdentityMarker;", true); annotation.visit("value", "original provenance"); annotation.visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
        MethodVisitor choose = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(Z)I", null, null);
        choose.visitParameter("flag", Opcodes.ACC_FINAL); choose.visitCode();
        Label second = new Label(); choose.visitVarInsn(Opcodes.ILOAD, 0); choose.visitJumpInsn(Opcodes.IFEQ, second);
        choose.visitInsn(Opcodes.ICONST_1); choose.visitInsn(Opcodes.IRETURN); choose.visitLabel(second);
        if (fullFrame) choose.visitFrame(Opcodes.F_FULL, 1, new Object[] {Opcodes.INTEGER}, 0, null);
        else choose.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        choose.visitInsn(Opcodes.ICONST_2); choose.visitInsn(Opcodes.IRETURN); choose.visitMaxs(1, 1); choose.visitEnd();
        MethodVisitor label = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "label", "()Ljava/lang/String;", null, null);
        label.visitCode(); label.visitLdcInsn("original behavior"); label.visitInsn(Opcodes.ARETURN); label.visitMaxs(1, 0); label.visitEnd();
        writer.visitEnd(); return writer.toByteArray();
    }
    private static ClassNode identityNode(byte[] bytes) {
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES); return node;
    }
    private static byte[] identityBytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static byte[] reserialize(byte[] bytes) {
        ClassNode node = identityNode(bytes); java.util.Collections.reverse(node.methods);
        ClassWriter writer = new ClassWriter(0); writer.newUTF8("synthetic unused pool entry"); node.accept(writer); return writer.toByteArray();
    }
    private static byte[] changedOpcode(byte[] bytes) {
        ClassNode node = identityNode(bytes);
        for (MethodNode method : node.methods) {
            // Both actual schemas read object locals. Avoid constructors, whose
            // uninitialized receiver follows different JVM verification rules.
            if (method.name.equals("<init>")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.ALOAD) {
                    method.instructions.set(instruction, new InsnNode(Opcodes.ACONST_NULL));
                    return identityBytes(node);
                }
            }
        }
        throw new AssertionError("No reference load available for executable drift fixture");
    }
    private static final class OpaqueFixtureAttribute extends org.objectweb.asm.Attribute {
        OpaqueFixtureAttribute() { super("SyntheticOpaqueFixture"); }
        @Override protected org.objectweb.asm.ByteVector write(ClassWriter writer, byte[] code, int length, int maxStack, int maxLocals) {
            return new org.objectweb.asm.ByteVector().putInt(42);
        }
    }
    private static void hooks() throws Exception {
        InstrumentationFixture normal = new InstrumentationFixture();
        HookInstallation installation = HookInstallation.prepare(normal.proxy, normal.target);
        check(normal.transformers.isEmpty() && normal.batches.size() == 1, "capture transformer removed after one complete class batch");
        installation.install(); installation.verifyReady();
        check(normal.transformers.size() == 1 && normal.batches.size() == 2 && normal.allChanged(), "hook installation uses one atomic whole-set retransformation");
        Map<Class<?>, byte[]> prepared = normal.copyCurrent();
        normal.replay(); check(normal.sameCurrent(prepared), "retransformation reuses isolated prepared output without mutation");
        installation.close(); check(normal.transformers.isEmpty() && normal.originalCurrent(), "close removes only own transformer and restores ordinary transformation output");
        int batches = normal.batches.size(); installation.close(); check(normal.batches.size() == batches, "close is idempotent");
        expect(IOException.class, installation::verifyReady, "closed hook set never claims readiness");
        InstrumentationFixture reserialized = new InstrumentationFixture();
        HookInstallation normalized = HookInstallation.prepare(reserialized.proxy, reserialized.target);
        reserialized.mode = "reserialized"; normalized.install(); normalized.verifyReady();
        Map<Class<?>, byte[]> normalizedPrepared = reserialized.copyCurrent();
        reserialized.replay(); normalized.verifyReady();
        check(reserialized.sameCurrent(normalizedPrepared), "equivalent serialized input reuses the checked prepared hook output");
        normalized.close();
        check(reserialized.transformers.isEmpty() && !reserialized.originalCurrent() && reserialized.canonicalOriginalCurrent(),
            "retirement preserves ordinary reserialized upstream bytes without restoring cached raw definitions");
        for (String mode : List.of("capture-missing", "capture-duplicate", "capture-throw")) {
            InstrumentationFixture fixture = new InstrumentationFixture(); fixture.mode = mode;
            expect(Exception.class, () -> HookInstallation.prepare(fixture.proxy, fixture.target), "capture failure rejected: " + mode);
            check(fixture.transformers.isEmpty(), "capture failure always removes temporary observer: " + mode);
        }
        for (String mode : List.of("install-throw", "install-missing", "changed", "code-changed")) {
            InstrumentationFixture fixture = new InstrumentationFixture();
            HookInstallation failing = HookInstallation.prepare(fixture.proxy, fixture.target); fixture.mode = mode;
            expect(Exception.class, failing::install, "incomplete/changed installation fails closed: " + mode);
            check(fixture.transformers.isEmpty() && fixture.originalCurrent(), "failed install removes own transformer and restores ordinary bytes: " + mode);
            expect(IOException.class, failing::verifyReady, "failed installation cannot activate payload");
        }
        for (String drift : List.of("changed", "code-changed")) {
            InstrumentationFixture external = new InstrumentationFixture(); HookInstallation active = HookInstallation.prepare(external.proxy, external.target);
            active.install(); AtomicReference<Throwable> observed = new AtomicReference<>(); active.onFailure(observed::set);
            external.mode = drift; external.replay();
            check(observed.get() instanceof IOException, "post-install external byte change notifies lifecycle owner");
            expect(IOException.class, active::verifyReady, "external target change invalidates readiness");
            AtomicReference<Throwable> late = new AtomicReference<>(); active.onFailure(late::set);
            check(late.get() == observed.get(), "late observer receives exact original failure");
            external.mode = "upstream"; active.close();
            check(external.transformers.isEmpty() && !external.originalCurrent(), "retirement preserves new ordinary upstream transforms instead of redefining cached vanilla bytes");
        }
        for (String mode : List.of("remove-denied", "rollback-throw")) {
            InstrumentationFixture fixture = new InstrumentationFixture(); HookInstallation retry = HookInstallation.prepare(fixture.proxy, fixture.target);
            retry.install(); fixture.mode = mode;
            expect(mode.equals("rollback-throw") ? java.lang.instrument.UnmodifiableClassException.class : IOException.class,
                retry::close, "retirement failure remains observable: " + mode);
            expect(IOException.class, retry::verifyReady, "failed retirement never reports active hooks");
            check(fixture.allChanged(), "failed retirement does not pretend definitions were restored");
            fixture.mode = ""; retry.close();
            check(fixture.transformers.isEmpty() && fixture.originalCurrent(), "retry completes pending transformer removal/restoration: " + mode);
        }
    }

    private static void hostLifetimes() throws Exception {
        var current = field(BootstrapHost.class, "current");
        var attempted = field(BootstrapHost.class, "attempted");
        Object original = current.get(null); boolean originalAttempted = attempted.getBoolean(null);
        var available = BootstrapHost.class.getDeclaredMethod("requireAvailable"); available.setAccessible(true);
        Class<?> sessionType = Class.forName(BootstrapHost.class.getName() + "$Session");
        var constructor = sessionType.getDeclaredConstructor(Object.class, VerifiedBootstrapLoader.class,
            HookInstallation.class, java.util.function.Consumer.class, String.class); constructor.setAccessible(true);
        var clean = sessionType.getDeclaredMethod("cleanStopped"); clean.setAccessible(true);
        var status = sessionType.getDeclaredMethod("status"); status.setAccessible(true);
        var stop = sessionType.getDeclaredMethod("stop"); stop.setAccessible(true);
        InstrumentationFixture game = new InstrumentationFixture();
        java.util.ArrayDeque<Runnable> retirementQueue = new java.util.ArrayDeque<>();
        java.util.function.Consumer<Runnable> enqueue = retirementQueue::addLast;
        byte[] archive = jar(archiveEntries(), false);
        try {
            current.set(null, null); attempted.setBoolean(null, true);
            expect(IOException.class, () -> reflected(available, null), "unknown prior partial installation requires restart");
            attempted.setBoolean(null, false); reflected(available, null);
            for (int lifetime = 0; lifetime < 2; lifetime++) {
                HookInstallation hooks = HookInstallation.prepare(game.proxy, game.target); hooks.install();
                VerifiedBootstrapLoader internal = new VerifiedBootstrapLoader(archive, VerifiedBootstrapLoader.hash(archive), BootstrapACheck.class.getClassLoader());
                SessionHandle handle = new SessionHandle();
                Object session = constructor.newInstance(handle, internal, hooks, enqueue, "fixture-" + lifetime);
                current.set(null, session); attempted.setBoolean(null, true);
                handle.ready.complete(null);
                check(((Map<?, ?>) reflected(status, session)).get("phase").equals("active"), "fresh lifetime becomes active");
                expect(IOException.class, () -> reflected(available, null), "active lifetime cannot be replaced");
                reflected(stop, session);
                check(handle.stops == 1 && !(boolean) reflected(clean, session), "Stop requests cleanup without admitting a new lifetime early");
                expect(IOException.class, () -> reflected(available, null), "pending payload retirement prevents another load");
                handle.stopped.complete(null);
                check(retirementQueue.size() == 1 && !(boolean) reflected(clean, session)
                    && internal.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT) != null,
                    "B completion returns with bytes retained before queued A retirement");
                expect(IOException.class, () -> reflected(available, null), "queued final retirement still prevents next lifetime");
                retirementQueue.removeFirst().run();
                check((boolean) reflected(clean, session) && game.transformers.isEmpty() && game.originalCurrent(),
                    "completed payload cleanup restores the entire original hook chain on same game target");
                check(internal.getResourceAsStream(VerifiedBootstrapLoader.LAYOUT) == null, "successful retirement revokes old B bytes");
                for (String name : List.of("payload", "stop", "hooks", "internal")) {
                    check(field(sessionType, name).get(session) == null, "clean A session releases child ownership: " + name);
                }
                reflected(stop, session); check(handle.stops == 1, "stopped control is idempotent");
                reflected(available, null);
                check(current.get(null) == null && !attempted.getBoolean(null), "successful full stop admits a distinct next lifetime");
            }
            for (String failureMode : List.of("payload-failure", "remove-denied", "rollback-throw")) {
                HookInstallation hooks = HookInstallation.prepare(game.proxy, game.target); hooks.install();
                VerifiedBootstrapLoader internal = new VerifiedBootstrapLoader(archive, VerifiedBootstrapLoader.hash(archive), BootstrapACheck.class.getClassLoader());
                SessionHandle handle = new SessionHandle(); Object session = constructor.newInstance(handle, internal, hooks, enqueue, "failed");
                current.set(null, session); attempted.setBoolean(null, true);
                if (failureMode.equals("payload-failure")) handle.stopped.completeExceptionally(new IOException("fixture cleanup failure"));
                else { game.mode = failureMode; handle.stopped.complete(null); }
                check(retirementQueue.size() == 1, "failed/normal completion uses the same queue boundary");
                retirementQueue.removeFirst().run();
                check(!(boolean) reflected(clean, session) && ((Map<?, ?>) reflected(status, session)).get("phase").equals("restart required"),
                    "uncertain cleanup remains restart required: " + failureMode);
                check(field(sessionType, "payload").get(session) == handle && field(sessionType, "internal").get(session) == internal,
                    "failed cleanup retains payload and B ownership: " + failureMode);
                expect(IOException.class, () -> reflected(available, null), "failed cleanup cannot admit a next lifetime: " + failureMode);
                game.mode = ""; hooks.close(); internal.close(); // Isolated fixture only; no runtime is present.
            }
        } finally { current.set(null, original); attempted.setBoolean(null, originalAttempted); }
    }
    public static final class SessionHandle {
        final java.util.concurrent.CompletableFuture<Void> ready = new java.util.concurrent.CompletableFuture<>();
        final java.util.concurrent.CompletableFuture<Void> stopped = new java.util.concurrent.CompletableFuture<>();
        int stops;
        public java.util.concurrent.CompletionStage<Void> ready() { return ready; }
        public java.util.concurrent.CompletionStage<Void> stopped() { return stopped; }
        public java.util.concurrent.CompletionStage<Void> stop() { stops++; return stopped; }
    }
    private static java.lang.reflect.Field field(Class<?> type, String name) throws ReflectiveOperationException {
        var result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static Object reflected(java.lang.reflect.Method method, Object owner, Object... arguments) throws Exception {
        try { return method.invoke(owner, arguments); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof Exception failure) throw failure;
            if (error.getCause() instanceof Error failure) throw failure;
            throw new AssertionError(error);
        }
    }

    /** Synthetic hook-shaped bytes for host transactions without a Minecraft binary. */
    private static byte[] transactionSchema(String owner) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        if (owner.equals("net/minecraft/entity/Entity")) {
            MethodVisitor yaw = writer.visitMethod(Opcodes.ACC_PUBLIC, "getYaw", "()F", null, null);
            yaw.visitCode(); yaw.visitInsn(Opcodes.FCONST_0); yaw.visitInsn(Opcodes.FRETURN); yaw.visitMaxs(1, 1); yaw.visitEnd();
            MethodVisitor move = writer.visitMethod(Opcodes.ACC_PUBLIC, "updateVelocity", "(FLnet/minecraft/util/math/Vec3d;)V", null, null);
            move.visitCode(); move.visitVarInsn(Opcodes.ALOAD, 0); move.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "getYaw", "()F", false);
            move.visitInsn(Opcodes.POP); move.visitInsn(Opcodes.RETURN); move.visitMaxs(1, 3); move.visitEnd();
            MethodVisitor look = writer.visitMethod(Opcodes.ACC_PUBLIC, "changeLookDirection", "(DD)V", null, null);
            look.visitCode(); look.visitInsn(Opcodes.RETURN); look.visitMaxs(0, 5); look.visitEnd();
        } else if (owner.equals("net/minecraft/client/input/KeyboardInput")) {
            MethodVisitor tick = writer.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
            tick.visitCode(); tick.visitVarInsn(Opcodes.ALOAD, 0); tick.visitInsn(Opcodes.POP); tick.visitInsn(Opcodes.RETURN); tick.visitMaxs(1, 1); tick.visitEnd();
        } else throw new AssertionError("Unexpected synthetic transaction target");
        writer.visitEnd(); return writer.toByteArray();
    }

    private static final class InstrumentationFixture {
        final List<ClassFileTransformer> transformers = new ArrayList<>();
        final List<List<Class<?>>> batches = new ArrayList<>();
        final Map<Class<?>, byte[]> original = new IdentityHashMap<>(), current = new IdentityHashMap<>();
        final MinecraftTarget target;
        final Instrumentation proxy;
        String mode = "";
        InstrumentationFixture() throws Exception {
            DummyLoader loader = new DummyLoader(); List<Class<?>> classes = new ArrayList<>();
            for (String owner : List.of("net/minecraft/entity/Entity", "net/minecraft/client/input/KeyboardInput")) {
                Class<?> type = loader.define(owner.replace('/', '.'), tinyClass(owner)); classes.add(type);
                if (minecraftSchemas == null) original.put(type, transactionSchema(owner));
                else {
                    var entry = minecraftSchemas.getJarEntry(owner + ".class");
                    if (entry == null) throw new IOException("Missing supplied Minecraft schema " + owner);
                    try (InputStream input = minecraftSchemas.getInputStream(entry)) { original.put(type, input.readAllBytes()); }
                }
            }
            target = new MinecraftTarget(classes.getFirst(), loader, List.copyOf(classes)); current.putAll(original);
            proxy = (Instrumentation) Proxy.newProxyInstance(BootstrapACheck.class.getClassLoader(), new Class<?>[] { Instrumentation.class }, (object, method, args) -> {
                return switch (method.getName()) {
                    case "addTransformer" -> { check((boolean) args[1], "only retransformation-capable transformers installed"); transformers.add((ClassFileTransformer) args[0]); yield null; }
                    case "removeTransformer" -> mode.equals("remove-denied") ? false : transformers.remove((ClassFileTransformer) args[0]);
                    case "retransformClasses" -> { transform((Class<?>[]) args[0]); yield null; }
                    case "isRetransformClassesSupported", "isModifiableClass" -> true;
                    case "getAllLoadedClasses" -> classes.toArray(Class<?>[]::new);
                    default -> throw new AssertionError("unexpected instrumentation operation " + method.getName());
                };
            });
        }
        void replay() throws Exception { proxy.retransformClasses(target.targets().toArray(Class<?>[]::new)); }
        private void transform(Class<?>[] classes) throws Exception {
            batches.add(List.of(classes)); check(Set.copyOf(List.of(classes)).equals(Set.copyOf(target.targets())), "each capture/apply/rollback uses exact class set");
            boolean capturing = batches.size() == 1;
            Map<Class<?>, byte[]> next = new IdentityHashMap<>(); int index = 0;
            for (Class<?> type : classes) {
                byte[] bytes = original.get(type).clone();
                if (mode.equals("reserialized")) bytes = reserialize(bytes);
                if (mode.equals("code-changed") && !transformers.isEmpty()) bytes = changedOpcode(bytes);
                if (mode.equals("upstream") || mode.equals("changed") && !transformers.isEmpty()) bytes[bytes.length - 1] ^= 1;
                boolean omit = index++ == 0 && (capturing ? mode.equals("capture-missing") : mode.equals("install-missing"));
                if (!omit) for (ClassFileTransformer transformer : List.copyOf(transformers)) {
                    byte[] replacement = transformer.transform(type.getModule(), target.loader(), type.getName().replace('.', '/'), type, null, bytes);
                    if (capturing && mode.equals("capture-duplicate")) transformer.transform(type.getModule(), target.loader(), type.getName().replace('.', '/'), type, null, bytes);
                    if (replacement != null) bytes = replacement;
                }
                next.put(type, bytes);
            }
            if (capturing && mode.equals("capture-throw") || !capturing && !transformers.isEmpty() && mode.equals("install-throw")
                || !capturing && transformers.isEmpty() && mode.equals("rollback-throw")) {
                throw new java.lang.instrument.UnmodifiableClassException("simulated atomic retransformation failure");
            }
            current.clear(); current.putAll(next);
        }
        boolean allChanged() { return original.entrySet().stream().allMatch(entry -> !Arrays.equals(entry.getValue(), current.get(entry.getKey()))); }
        boolean canonicalOriginalCurrent() throws IOException {
            for (var entry : original.entrySet()) {
                if (!ClassFileIdentity.canonicalHash(entry.getValue()).equals(ClassFileIdentity.canonicalHash(current.get(entry.getKey())))) return false;
            }
            return true;
        }
        boolean originalCurrent() { return sameCurrent(original); }
        Map<Class<?>, byte[]> copyCurrent() { Map<Class<?>, byte[]> copy = new IdentityHashMap<>(); current.forEach((type, bytes) -> copy.put(type, bytes.clone())); return copy; }
        boolean sameCurrent(Map<Class<?>, byte[]> expected) { return expected.entrySet().stream().allMatch(entry -> Arrays.equals(entry.getValue(), current.get(entry.getKey()))); }
    }

    private static final class SupportFixture {
        final Map<String, byte[]> jars = new LinkedHashMap<>();
        final JsonObject contract = new JsonObject();
        boolean agentMismatch;
        SupportFixture() throws Exception {
            jars.put("celeste-beta-bridges.jar", jar(Map.of(BRIDGE, tinyClass(BRIDGE.substring(0, BRIDGE.length() - 6))), false));
            jars.put("jna.jar", jar(Map.of("com/sun/jna/Native.class", tinyClass("com/sun/jna/Native"), "com/sun/jna/probe.txt", new byte[] { 42 }), false));
            jars.put("oshi.jar", jar(Map.of("oshi/SystemInfo.class", tinyClass("oshi/SystemInfo")), false));
            for (String name : List.of("jna-platform.jar", "media.jar", "support.jar")) jars.put(name, jar(Map.of("fixture.txt", bytes(name)), false));
            contract.addProperty("schema", "celeste-beta-host-support-1");
            contract.add("bootstrap_classpath", array(List.of("celeste-beta-bridges.jar")));
            contract.add("resident_url_classpath", array(jars.keySet().stream().filter(name -> !name.equals("celeste-beta-bridges.jar")).toList()));
            contract.add("resident_owned_prefixes", array(List.of("com.sun.jna.", "oshi.", "dev.redstones.mediaplayerinfo.")));
            contract.add("resident_resource_prefixes", array(List.of("com/sun/jna/", "oshi/", "mediaplayerinfo/", "dev/redstones/mediaplayerinfo/")));
            contract.addProperty("parent", "actual verified Minecraft/Fabric game classloader");
            contract.addProperty("must_validate_from_retained_payload", true); contract.addProperty("nested_jars_are_not_automatically_classpath_entries", true);
            descriptors();
        }
        void replaceBridge(byte[] bytes) { jars.put("celeste-beta-bridges.jar", bytes); descriptors(); }
        private void descriptors() {
            JsonArray libraries = new JsonArray(); jars.forEach((name, bytes) -> {
                JsonObject entry = new JsonObject(); entry.addProperty("filename", name); entry.addProperty("size", bytes.length);
                entry.addProperty("sha256", VerifiedBootstrapLoader.hash(bytes)); entry.addProperty("payload_backing", "META-INF/celeste-beta/host-jars/" + name);
                entry.addProperty("bootstrap_entry", "META-INF/celeste-beta/host-jars/" + name); libraries.add(entry);
            }); contract.add("libraries", libraries);
        }
        TwoPartMemoryClassLoader retained() throws Exception {
            Map<String, byte[]> entries = new LinkedHashMap<>(); entries.put(ResidentSupport.CONTRACT, bytes(contract.toString()));
            jars.forEach((name, value) -> entries.put("META-INF/celeste-beta/host-jars/" + name, value));
            byte[] first = jar(entries, false), second = jar(Map.of("fixture/payload/Anchor.class", tinyClass("fixture/payload/Anchor")), false);
            return TwoPartMemoryClassLoader.open(first, VerifiedBootstrapLoader.hash(first), second, VerifiedBootstrapLoader.hash(second),
                new TwoPartMemoryClassLoader.Namespaces(Set.of("fixture.payload."), Set.of("META-INF/celeste-beta/")),
                TwoPartMemoryClassLoader.Limits.betaDefaults(), BootstrapACheck.class.getClassLoader());
        }
        ClassLoader agent() {
            return new ClassLoader(BootstrapACheck.class.getClassLoader()) {
                @Override public InputStream getResourceAsStream(String path) {
                    byte[] value = jars.get(path.substring(path.lastIndexOf('/') + 1));
                    if (value == null) return null; value = value.clone(); if (agentMismatch) value[0] ^= 1;
                    return new ByteArrayInputStream(value);
                }
            };
        }
    }
    private static final class DummyLoader extends ClassLoader {
        DummyLoader() { super(BootstrapACheck.class.getClassLoader()); }
        Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
    }
    private static byte[] tinyClass(String owner) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0xcafebabe); out.writeShort(0); out.writeShort(65); out.writeShort(5);
            out.writeByte(1); out.writeUTF(owner); out.writeByte(7); out.writeShort(1);
            out.writeByte(1); out.writeUTF("java/lang/Object"); out.writeByte(7); out.writeShort(3);
            out.writeShort(0x21); out.writeShort(2); out.writeShort(4);
            out.writeShort(0); out.writeShort(0); out.writeShort(0); out.writeShort(0);
        }
        return bytes.toByteArray();
    }
    private static Map<String, byte[]> archiveEntries() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\nCeleste-Beta-Bootstrap: B\r\n\r\n"));
        entries.put(VerifiedBootstrapLoader.LAYOUT, bytes("bootstrap fixture mapping marker"));
        entries.put(INTERNAL.replace('.', '/') + ".class", tinyClass(INTERNAL.replace('.', '/')));
        org.objectweb.asm.ClassWriter capability = new org.objectweb.asm.ClassWriter(0);
        capability.visit(65, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
            INTERNAL.replace('.', '/') + "$PreparedGame", null, "java/lang/Object", null);
        capability.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
            "verifyReadyForPayload", "()V", null, new String[] {"java/lang/Exception"}).visitEnd();
        capability.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
            "runtimeParent", "()Ljava/lang/ClassLoader;", null, null).visitEnd();
        capability.visitEnd(); entries.put(INTERNAL.replace('.', '/') + "$PreparedGame.class", capability.toByteArray());
        return entries;
    }
    private static byte[] jar(Map<String, byte[]> entries, boolean stored) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                JarEntry member = new JarEntry(entry.getKey()); member.setTime(0);
                if (stored) { CRC32 crc = new CRC32(); crc.update(entry.getValue()); member.setMethod(ZipEntry.STORED); member.setSize(entry.getValue().length); member.setCrc(crc.getValue()); }
                output.putNextEntry(member); output.write(entry.getValue()); output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    private static int indexOf(byte[] bytes, byte[] needle) {
        for (int offset = 0; offset <= bytes.length - needle.length; offset++) {
            if (Arrays.equals(bytes, offset, offset + needle.length, needle, 0, needle.length)) return offset;
        }
        throw new AssertionError("fixture marker not found");
    }
    private static JsonArray array(List<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void rejectArchive(byte[] bytes, String label) {
        expect(IOException.class, () -> { try (VerifiedBootstrapLoader ignored = new VerifiedBootstrapLoader(bytes, VerifiedBootstrapLoader.hash(bytes), BootstrapACheck.class.getClassLoader())) { if (ignored != null) throw new AssertionError("invalid archive accepted"); } }, label);
    }
    private static long childCount(Path directory) throws IOException { try (var paths = Files.list(directory)) { return paths.count(); } }
    private static void eraseFixture(Path root) throws IOException {
        Path exact = root.toRealPath();
        try (var paths = Files.walk(exact)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(exact)) throw new IOException("fixture cleanup escaped root");
                Files.delete(path);
            }
        }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static Throwable expect(Class<? extends Throwable> type, Throwing action, String label) {
        checks++; try { action.run(); }
        catch (Throwable failure) { if (type.isInstance(failure)) return failure; throw new AssertionError(label, failure); }
        throw new AssertionError(label + ": no exception");
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
