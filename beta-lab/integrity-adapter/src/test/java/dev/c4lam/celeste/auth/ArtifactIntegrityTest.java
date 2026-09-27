package dev.c4lam.celeste.auth;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Compiles the production integrity class and Crypto, not copied implementations. */
public final class ArtifactIntegrityTest {
    private static final String MUTABLE = "META-INF/.r/4d7a9c2e";
    private static int assertions;
    private static Path scratch;
    private static int artifactId;

    private ArtifactIntegrityTest() {
    }

    public static void main(String[] arguments) throws Exception {
        scratch = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(scratch);
        String mode = arguments.length > 1 ? arguments[1] : "full";
        switch (mode) {
            case "full" -> {
                parityAndExclusions();
                duplicateRejection();
                defensiveInputs();
                releaseSizedInputs();
                snapshotOwnership();
                oneShotLifecycle(); // Must run last: source selection cannot be undone.
            }
            case "disk-first" -> diskFirstPinning();
            case "disk-failure" -> failedDiskPinning();
            case "memory-failure" -> failedMemoryPinning();
            case "source-race" -> sourceSelectionRace();
            default -> throw new IllegalArgumentException("Unknown isolated test mode: " + mode);
        }
        System.out.println("PASS: ArtifactIntegrity " + mode + " (" + assertions + " assertions)");
    }

    private static void parityAndExclusions() throws Exception {
        Map<String, byte[]> first = first();
        Map<String, byte[]> second = second();
        String expected = referenceDigest(first, second);
        check(diskDigest(first, second).equals(expected), "Existing split-JAR framing matches independent reference");
        check(memoryDigest(first, second).equals(expected), "Retained-memory and split-JAR parity");
        check(memoryDigest(reversed(second), reversed(first)).equals(expected), "Part and map order do not affect digest");
        check(diskDigest(reversed(second), reversed(first)).equals(expected), "Part and ZIP entry order do not affect digest");
        Map<String, byte[]> combined = new LinkedHashMap<>(first);
        combined.putAll(second);
        Path combinedJar = writeJar(combined);
        check(directDiskDigest("digestJar", combinedJar).equals(expected), "Single JAR has identical framing");
        Path directory = scratch.resolve("directory-fixture");
        for (Map.Entry<String, byte[]> entry : combined.entrySet()) {
            Path destination = directory.resolve(entry.getKey());
            if (entry.getKey().endsWith("/")) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.write(destination, entry.getValue());
            }
        }
        check(directDiskDigest("digestDirectory", directory).equals(expected), "Directory path has identical framing");
        first.put(MUTABLE, bytes("different descriptor bytes"));
        second.put(MUTABLE, bytes("both descriptor copies are excluded"));
        first.put("empty-directory/", bytes("directory content remains excluded like JarEntry.isDirectory"));
        check(memoryDigest(first, second).equals(expected), "Only descriptor and directories are excluded");
        check(diskDigest(first, second).equals(expected), "Excluded metadata agrees with existing disk path");
        for (String protectedName : List.of("META-INF/MANIFEST.MF", "META-INF/SIGNER.SF", "fabric.mod.json")) {
            Map<String, byte[]> changed = new LinkedHashMap<>(combined);
            changed.put(protectedName, bytes("modified protected metadata"));
            String changedDigest = memoryDigest(changed, Map.of());
            check(!changedDigest.equals(expected), protectedName + " remains protected");
            check(changedDigest.equals(diskDigest(changed, Map.of())), protectedName + " changed parity");
        }
        Map<String, byte[]> changedClass = new LinkedHashMap<>(combined);
        changedClass.get("a/A.class")[0] ^= 1;
        check(!memoryDigest(changedClass, Map.of()).equals(expected), "Class mutation changes recomputed digest");
        check(memoryDigest(Map.of(), Map.of()).equals(Crypto.hex(MessageDigest.getInstance("SHA-256").digest())),
            "Empty protected set retains SHA-256 empty framing");
        check(!memoryDigest(Map.of("a", bytes("bc")), Map.of()).equals(memoryDigest(Map.of("ab", bytes("c")), Map.of())),
            "Length framing prevents name/content concatenation ambiguity");
    }

    private static void duplicateRejection() throws Exception {
        Map<String, byte[]> duplicate = Map.of("a/A.class", bytes("same name"));
        expect(IOException.class, () -> memoryDigest(duplicate, duplicate), "Duplicate protected entry across memory parts");
        expect(IllegalStateException.class, () -> diskDigest(duplicate, duplicate), "Existing disk duplicate rejection unchanged");
        Map<String, byte[]> malformedMap = new AbstractMap<>() {
            @Override
            public Set<Map.Entry<String, byte[]>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Map.Entry<String, byte[]>> iterator() {
                        return List.of(Map.entry("a/A.class", bytes("one")), Map.entry("a/A.class", bytes("two"))).iterator();
                    }
                    @Override
                    public int size() { return 2; }
                };
            }
        };
        expect(IOException.class, () -> memoryDigest(malformedMap, Map.of()), "Duplicate enumeration within a retained part");
        Path source = writeJar(Map.of("a/A.class", bytes("one"), "b/A.class", bytes("two")));
        byte[] archive = Files.readAllBytes(source);
        replaceEqualLength(archive, bytes("b/A.class"), bytes("a/A.class"));
        Path duplicateArchive = scratch.resolve("duplicate-protected.jar");
        Files.write(duplicateArchive, archive);
        expect(IllegalStateException.class, () -> diskDigestPaths(List.of(duplicateArchive)), "Duplicate inside existing split-JAR path");
        check(memoryDigest(Map.of(MUTABLE, bytes("one")), Map.of(MUTABLE, bytes("two")))
            .equals(diskDigest(Map.of(MUTABLE, bytes("one")), Map.of(MUTABLE, bytes("two")))),
            "Duplicate excluded descriptors are not broadened to duplicate-protected rejection");
    }

    private static void defensiveInputs() throws Exception {
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> { }), "Zero parts rejected");
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> visitor.accept(Map.of())), "One part rejected");
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> {
            visitor.accept(Map.of()); visitor.accept(Map.of()); visitor.accept(Map.of());
        }), "Three parts rejected");
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> visitor.accept(null)), "Null part rejected");
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put(null, bytes("x"));
        expect(IOException.class, () -> memoryDigest(invalid, Map.of()), "Null entry name rejected");
        invalid.clear(); invalid.put("x", null);
        expect(IOException.class, () -> memoryDigest(invalid, Map.of()), "Null bytes rejected");
        expect(IOException.class, () -> memoryDigest(Map.of("x".repeat(1025), bytes("x")), Map.of()), "Bounded name length");
        check(memoryDigest(Map.of("x".repeat(1024), bytes("x")), Map.of()).length() == 64, "Exact name limit accepted");
        byte[] oversized = new byte[8 * 1024 * 1024 + 1];
        expect(IOException.class, () -> memoryDigest(Map.of("oversized", oversized), Map.of()), "Per-entry byte limit enforced");
        expect(IOException.class, () -> memoryDigest(Map.of(MUTABLE, oversized), Map.of()), "Excluded bytes cannot evade byte budget");
        Map<String, byte[]> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 65_537; index++) tooMany.put("entry/" + index, new byte[0]);
        expect(IOException.class, () -> memoryDigest(tooMany, Map.of()), "Combined entry-count limit enforced");
        tooMany.remove("entry/65536");
        check(memoryDigest(tooMany, Map.of()).length() == 64, "Exact entry-count limit accepted");
        byte[] large = new byte[8 * 1024 * 1024];
        Map<String, byte[]> largePart = new LinkedHashMap<>();
        for (int index = 0; index < 16; index++) largePart.put("large/" + index, large);
        check(memoryDigest(largePart, Map.of()).length() == 64, "Exact 128 MiB total and 8 MiB entry limits accepted");
        expect(IOException.class, () -> memoryDigest(largePart, Map.of("overflow", bytes("x"))), "Combined total limit enforced");
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> {
            visitor.accept(Map.of("same", bytes("x")));
            try { visitor.accept(Map.of("same", bytes("y"))); } catch (IOException intentionallySwallowed) { }
        }), "Swallowed visitor failure remains sticky and fails closed");
        AtomicReference<ArtifactIntegrity.PartVisitor> escaped = new AtomicReference<>();
        ArtifactIntegrity.digestVerifiedMemory(visitor -> {
            escaped.set(visitor); visitor.accept(Map.of()); visitor.accept(Map.of());
        });
        expect(IOException.class, () -> escaped.get().accept(Map.of()), "Escaped visitor is revoked after synchronous visit");
        expect(IOException.class, () -> ArtifactIntegrity.digestVerifiedMemory(visitor -> {
            visitor.accept(first()); throw new IOException("source revoked during snapshot");
        }), "Source failures propagate");
    }

    private static void releaseSizedInputs() throws Exception {
        // Synthetic inputs reproduce measured release counts/expanded byte totals
        // without opening or expanding the real release archives. Shared source
        // arrays keep fixture allocation small; adapter snapshots are still real.
        Map<String, byte[]> first = sizedPart("first/", 22_336, 42_793_344);
        Map<String, byte[]> second = sizedPart("second/", 22_435, 34_694_388);
        check(first.size() + second.size() == 44_771, "Release-sized synthetic entry count");
        check(first.values().stream().mapToLong(bytes -> bytes.length).sum()
            + second.values().stream().mapToLong(bytes -> bytes.length).sum() == 77_487_732,
            "Release-sized synthetic expanded byte count");
        check(memoryDigest(first, second).length() == 64, "Actual-release-sized retained input accepted within fixed bounds");
    }

    private static Map<String, byte[]> sizedPart(String prefix, int count, int totalBytes) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        byte[] common = new byte[totalBytes / count];
        Arrays.fill(common, (byte) 0x51);
        for (int index = 0; index < count - 1; index++) result.put(prefix + index, common);
        result.put(prefix + (count - 1), new byte[common.length + totalBytes % count]);
        return result;
    }

    private static void snapshotOwnership() throws Exception {
        Map<String, byte[]> first = first();
        Map<String, byte[]> second = second();
        byte[] retained = first.get("z/Z.class");
        byte[] before = retained.clone();
        String expected = memoryDigest(first, second);
        check(Arrays.equals(before, retained), "Successful digest never wipes or modifies retained arrays");
        expect(IOException.class, () -> memoryDigest(first, first), "Failed snapshot rejects duplicate source");
        check(Arrays.equals(before, retained), "Failed digest never wipes or modifies retained arrays");
        String copied = ArtifactIntegrity.digestVerifiedMemory(visitor -> {
            visitor.accept(first);
            visitor.accept(second);
            // The lifecycle can release after completing its snapshot visit.
            Arrays.fill(retained, (byte) 0);
        });
        check(copied.equals(expected), "Adapter owns stable copies for sorted hashing after source release");
        check(!memoryDigest(first, second).equals(expected), "Next digest recomputes current retained bytes, with no cached digest");
    }

    private static void oneShotLifecycle() throws Exception {
        MutableSource source = new MutableSource(first(), second());
        String expected = memoryDigest(source.first, source.second);
        Path disk = writeJar(Map.of("unrelated", bytes("disk fallback must not be used")));
        String originalProperty = System.getProperty("celeste.payload.parts");
        System.setProperty("celeste.payload.parts", disk.toString());
        try {
            ArtifactIntegrity.installVerifiedMemorySource(source);
            expect(IllegalStateException.class, () -> ArtifactIntegrity.installVerifiedMemorySource(source),
                "Source cannot be reinstalled even before first authentication digest");
            check(ArtifactIntegrity.digest().equals(expected), "Installed retained source overrides disk property");
            source.first.get("z/Z.class")[0] ^= 1;
            check(!ArtifactIntegrity.digest().equals(expected), "Authentication digest observes retained-byte mutation");
            expect(IllegalStateException.class, () -> ArtifactIntegrity.installVerifiedMemorySource(source), "Reinstallation rejected");
            source.close();
            expect(IOException.class, ArtifactIntegrity::digest, "Closed source fails instead of disk fallback");
            expect(IllegalStateException.class, () -> ArtifactIntegrity.installVerifiedMemorySource(visitor -> {
                visitor.accept(Map.of()); visitor.accept(Map.of());
            }), "Closed source cannot be replaced");
        } finally {
            restoreProperty(originalProperty);
        }
    }

    /** Each mode runs in a fresh JVM so no test can reset production source state. */
    private static void diskFirstPinning() throws Exception {
        Map<String, byte[]> diskEntries = Map.of("disk-only", bytes("authenticated disk bytes"));
        Path disk = writeJar(diskEntries);
        String expectedDisk = referenceDigest(diskEntries, Map.of());
        String expectedMemory = memoryDigest(first(), second());
        check(!expectedDisk.equals(expectedMemory), "Disk/memory pinning fixture digests differ");
        String original = System.getProperty("celeste.payload.parts");
        System.setProperty("celeste.payload.parts", disk.toString());
        try {
            check(ArtifactIntegrity.digest().equals(expectedDisk), "Actual digest selects existing disk fallback before installation");
            expect(IllegalStateException.class,
                () -> ArtifactIntegrity.installVerifiedMemorySource(new MutableSource(first(), second())),
                "First disk digest permanently rejects later memory source installation");
            check(ArtifactIntegrity.digest().equals(expectedDisk), "Rejected installation cannot replace subsequent disk heartbeat bytes");
            check(memoryDigest(first(), second()).equals(expectedMemory), "Stateless memory helper still works after disk source pinning");
            check(ArtifactIntegrity.digest().equals(expectedDisk), "Stateless helper cannot change pinned authentication source");
        } finally {
            restoreProperty(original);
        }
    }

    private static void failedDiskPinning() throws Exception {
        String original = System.getProperty("celeste.payload.parts");
        Path missing = scratch.resolve("missing-" + java.util.UUID.randomUUID() + ".jar");
        System.setProperty("celeste.payload.parts", missing.toString());
        try {
            expect(IOException.class, ArtifactIntegrity::digest, "Missing disk artifact fails actual first digest");
            expect(IllegalStateException.class,
                () -> ArtifactIntegrity.installVerifiedMemorySource(new MutableSource(first(), second())),
                "Failed first disk digest also permanently pins source selection");
            Map<String, byte[]> recovered = Map.of("disk-only", bytes("readable disk fixture"));
            System.setProperty("celeste.payload.parts", writeJar(recovered).toString());
            check(ArtifactIntegrity.digest().equals(referenceDigest(recovered, Map.of())),
                "Pinned disk mode retains existing disk-property lookup behavior");
        } finally {
            restoreProperty(original);
        }
    }

    private static void failedMemoryPinning() throws Exception {
        String original = System.getProperty("celeste.payload.parts");
        System.setProperty("celeste.payload.parts", writeJar(Map.of("disk-only", bytes("no fallback"))).toString());
        try {
            ArtifactIntegrity.installVerifiedMemorySource(visitor -> { throw new IOException("source revoked before first digest"); });
            expect(IOException.class, ArtifactIntegrity::digest, "Installed source failure fails actual first digest");
            expect(IllegalStateException.class,
                () -> ArtifactIntegrity.installVerifiedMemorySource(new MutableSource(first(), second())),
                "Failed first memory digest cannot be replaced");
            check(memoryDigest(first(), second()).length() == 64, "Stateless helper remains independent of failed installed source");
            expect(IOException.class, ArtifactIntegrity::digest, "Failed memory source stays pinned despite valid disk and helper input");
        } finally {
            restoreProperty(original);
        }
    }

    private static void sourceSelectionRace() throws Exception {
        Map<String, byte[]> diskEntries = Map.of("disk-only", bytes("disk wins"));
        Path disk = writeJar(diskEntries);
        String expectedDisk = referenceDigest(diskEntries, Map.of());
        Map<String, byte[]> first = first();
        Map<String, byte[]> second = second();
        String expectedMemory = memoryDigest(first, second);
        String original = System.getProperty("celeste.payload.parts");
        System.setProperty("celeste.payload.parts", disk.toString());
        ExecutorService workers = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().factory());
        int memoryWins = 0;
        try {
            for (int round = 0; round < 128; round++) {
                // Each isolated definition has genuinely fresh static state. No
                // reflective writes, production resets, or replaced digest bodies.
                try (URLClassLoader isolated = new URLClassLoader(
                    new java.net.URL[] {ArtifactIntegrity.class.getProtectionDomain().getCodeSource().getLocation()},
                    ClassLoader.getPlatformClassLoader())) {
                    Class<?> integrity = Class.forName(ArtifactIntegrity.class.getName(), true, isolated);
                    Class<?> sourceType = Class.forName(ArtifactIntegrity.VerifiedMemorySource.class.getName(), true, isolated);
                    Class<?> visitorType = Class.forName(ArtifactIntegrity.PartVisitor.class.getName(), true, isolated);
                    Method accept = visitorType.getMethod("accept", Map.class);
                    Object source = Proxy.newProxyInstance(isolated, new Class<?>[] {sourceType}, (proxy, method, values) -> {
                        if (!method.getName().equals("visitParts")) throw new UnsupportedOperationException(method.getName());
                        invokeMethod(accept, values[0], first);
                        invokeMethod(accept, values[0], second);
                        return null;
                    });
                    Method install = integrity.getMethod("installVerifiedMemorySource", sourceType);
                    Method digest = integrity.getDeclaredMethod("digest");
                    digest.setAccessible(true);
                    CyclicBarrier start = new CyclicBarrier(2);
                    Future<Boolean> installed = workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        try { invokeMethod(install, null, source); return true; }
                        catch (IllegalStateException expectedWhenDiskWins) { return false; }
                    });
                    Future<String> observed = workers.submit(() -> {
                        start.await(5, TimeUnit.SECONDS);
                        return (String) invokeMethod(digest, null);
                    });
                    boolean memorySelected = installed.get(10, TimeUnit.SECONDS);
                    if (memorySelected) memoryWins++;
                    String firstDigest = observed.get(10, TimeUnit.SECONDS);
                    check(firstDigest.equals(memorySelected ? expectedMemory : expectedDisk),
                        "Concurrent install/digest must agree on a single source in round " + round);
                    check(invokeMethod(digest, null).equals(firstDigest),
                        "Race-selected source remains pinned for heartbeat in round " + round);
                    expect(IllegalStateException.class, () -> invokeMethod(install, null, source),
                        "Race-selected source rejects replacement in round " + round);
                }
            }
        } finally {
            workers.shutdownNow();
            restoreProperty(original);
        }
        System.out.println("Source race: 128 isolated rounds; memory wins=" + memoryWins + ", disk wins=" + (128 - memoryWins));
    }

    private static Map<String, byte[]> first() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("z/Z.class", new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, 65});
        result.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"));
        result.put("assets/celeste/星☃.txt", bytes("pixel:\u0000🟦"));
        result.put(MUTABLE, bytes("mutable descriptor one"));
        result.put("empty-directory/", new byte[0]);
        return result;
    }

    private static Map<String, byte[]> second() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        result.put("a/A.class", new byte[] {1, 2, 3});
        result.put("META-INF/SIGNER.SF", bytes("protected signature metadata"));
        result.put("fabric.mod.json", bytes("{\"id\":\"fixture\"}"));
        result.put(MUTABLE, bytes("mutable descriptor two"));
        result.put("empty-directory/", new byte[0]);
        return result;
    }

    private static String memoryDigest(Map<String, byte[]> first, Map<String, byte[]> second) throws IOException {
        return ArtifactIntegrity.digestVerifiedMemory(visitor -> { visitor.accept(first); visitor.accept(second); });
    }

    private static String diskDigest(Map<String, byte[]> first, Map<String, byte[]> second) throws Exception {
        return diskDigestPaths(List.of(writeJar(first), writeJar(second)));
    }

    private static String diskDigestPaths(List<Path> paths) throws Exception {
        // Compare the unchanged production disk implementation without selecting
        // the authentication source for this JVM. Pinning has dedicated modes.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Method method = ArtifactIntegrity.class.getDeclaredMethod("digestJars", List.class, MessageDigest.class);
        method.setAccessible(true);
        invokeMethod(method, null, paths, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void restoreProperty(String before) {
        if (before == null) System.clearProperty("celeste.payload.parts");
        else System.setProperty("celeste.payload.parts", before);
    }

    private static String directDiskDigest(String methodName, Path source) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Method method = ArtifactIntegrity.class.getDeclaredMethod(methodName, Path.class, MessageDigest.class);
        method.setAccessible(true);
        invokeMethod(method, null, source, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Object invokeMethod(Method method, Object receiver, Object... arguments) throws Exception {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw new AssertionError("Unexpected invocation failure", exception.getCause());
        }
    }

    private static Path writeJar(Map<String, byte[]> entries) throws IOException {
        Path result = scratch.resolve("fixture-" + artifactId++ + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(result))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return result;
    }

    private static Map<String, byte[]> reversed(Map<String, byte[]> source) {
        List<String> names = new ArrayList<>(source.keySet());
        Collections.reverse(names);
        Map<String, byte[]> result = new LinkedHashMap<>();
        names.forEach(name -> result.put(name, source.get(name)));
        return result;
    }

    private static String referenceDigest(Map<String, byte[]> first, Map<String, byte[]> second) throws Exception {
        Map<String, byte[]> combined = new java.util.TreeMap<>(first);
        combined.putAll(second);
        ByteArrayOutputStream framing = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(framing)) {
            for (Map.Entry<String, byte[]> entry : combined.entrySet()) {
                if (entry.getKey().endsWith("/") || entry.getKey().equals(MUTABLE)) continue;
                byte[] encodedName = bytes(entry.getKey());
                output.writeInt(encodedName.length);
                output.write(encodedName);
                output.writeLong(entry.getValue().length);
                output.write(entry.getValue());
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(framing.toByteArray()));
    }

    private static void replaceEqualLength(byte[] archive, byte[] from, byte[] to) {
        int replacements = 0;
        for (int offset = 0; offset <= archive.length - from.length; offset++) {
            if (Arrays.equals(archive, offset, offset + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, archive, offset, to.length);
                replacements++;
            }
        }
        check(replacements == 2, "Duplicate ZIP fixture patches local and central names");
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
    }

    private static void expect(Class<? extends Throwable> expected, Checked operation, String message) throws Exception {
        try { operation.run(); }
        catch (Throwable thrown) {
            if (expected.isInstance(thrown)) { assertions++; return; }
            throw new AssertionError(message + ": unexpected exception", thrown);
        }
        throw new AssertionError(message + ": did not reject");
    }

    @FunctionalInterface
    private interface Checked { void run() throws Exception; }

    private static final class MutableSource implements ArtifactIntegrity.VerifiedMemorySource, AutoCloseable {
        private final Map<String, byte[]> first;
        private final Map<String, byte[]> second;
        private boolean closed;

        private MutableSource(Map<String, byte[]> first, Map<String, byte[]> second) {
            this.first = first; this.second = second;
        }

        @Override
        public synchronized void visitParts(ArtifactIntegrity.PartVisitor visitor) throws IOException {
            if (closed) throw new IOException("retained source closed");
            visitor.accept(first); visitor.accept(second);
        }

        @Override
        public synchronized void close() {
            closed = true;
            first.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            second.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
            first.clear(); second.clear();
        }
    }
}
