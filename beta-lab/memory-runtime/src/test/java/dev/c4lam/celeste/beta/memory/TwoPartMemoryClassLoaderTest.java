package dev.c4lam.celeste.beta.memory;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** No external dependencies: generates fixture sources, classes, and JARs in memory. */
public final class TwoPartMemoryClassLoaderTest {
    private static final TwoPartMemoryClassLoader.Namespaces NAMESPACES =
        new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("assets/beta/"));
    private static final TwoPartMemoryClassLoader.Limits LIMITS =
        new TwoPartMemoryClassLoader.Limits(1_000_000, 100, 100_000, 500_000);
    private static int passed;

    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 21, "Run these checks on JDK 21");
        Map<String, byte[]> classes = compile(Map.of(
            "demo.payload.Left", """
                package demo.payload;
                public final class Left {
                    public static String value() { return "left+" + Right.value(); }
                    public static String asset() throws Exception {
                        try (var in = Left.class.getResourceAsStream("/assets/beta/right.txt")) {
                            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    }
                }
                """,
            "demo.payload.Right", """
                package demo.payload;
                public final class Right {
                    public static String value() { return "right"; }
                }
                """,
            "demo.payload.Late", """
                package demo.payload;
                public final class Late { public static String value() { return "late"; } }
                """
        ));
        byte[] first = jar(Map.of(
            "demo/payload/Left.class", classes.get("demo.payload.Left"),
            "assets/beta/left.txt", utf8("left resource")
        ));
        byte[] second = jar(Map.of(
            "demo/payload/Right.class", classes.get("demo.payload.Right"),
            "demo/payload/Late.class", classes.get("demo.payload.Late"),
            "assets/beta/right.txt", utf8("right resource")
        ));

        run("two-part classes and in-memory resources", () -> {
            byte[] firstOriginal = first.clone();
            try (TwoPartMemoryClassLoader loader = open(first, second)) {
                Class<?> left = loader.loadClass("demo.payload.Left");
                check(left.getClassLoader() == loader, "Owned class must be child-loaded");
                check(left.getMethod("value").invoke(null).equals("left+right"), "Cross-part class dependency");
                check(left.getMethod("asset").invoke(null).equals("right resource"), "Class resource lookup");
                check(loader.loadClass("java.lang.String") == String.class, "JDK dependency stays parent-loaded");
                check(read(loader.getResourceAsStream("assets/beta/left.txt")).equals("left resource"), "Part one resource");
                URL resource = loader.getResource("assets/beta/right.txt");
                check(resource != null && resource.getProtocol().equals("memoryjar"), "Non-file, non-network URL");
                check(read(resource.openStream()).equals("right resource"), "Part two URL stream");
                check(read(relativeUrl(resource, "left.txt").openStream()).equals("left resource"), "Relative resource URLs resolve their own paths");
                expect(IOException.class, () -> relativeUrl(resource, "missing.txt").openStream());
                expect(IOException.class, () -> relativeUrl(resource, "../../../../outside.txt").openStream());
                check(Collections.list(loader.getResources("assets/beta/right.txt")).size() == 1, "Resource enumeration");
                check(loader.getResource("/assets/beta/right.txt") == null, "ClassLoader path must be relative");
                check(loader.getResource("assets/beta/missing.txt") == null, "Missing resource");
                check(loader.retainedEntryCount() == 5 && loader.retainedByteCount() > 0, "Retained entry accounting");
                check(Arrays.equals(first, firstOriginal), "Caller input was not modified");
            }
        });

        run("ordinary package-info classes", () -> {
            Map<String, byte[]> metadata = compile(Map.of("demo.payload.package-info", "@Deprecated package demo.payload;"));
            byte[] metadataJar = jar(Map.of("demo/payload/package-info.class", metadata.get("demo.payload.package-info")));
            try (TwoPartMemoryClassLoader loader = open(metadataJar, second)) {
                check(loader.loadClass("demo.payload.package-info").getClassLoader() == loader, "Package metadata can load");
            }
        });

        run("payload namespace is exclusive; parent dependencies still work", () -> {
            ClassLoader observingParent = new ClassLoader(TwoPartMemoryClassLoaderTest.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.startsWith("demo.payload.")) throw new AssertionError("Owned class delegated to parent");
                    return super.loadClass(name, resolve);
                }
                @Override
                public URL getResource(String name) {
                    if (name.startsWith("assets/beta/")) throw new AssertionError("Owned resource delegated to parent");
                    return super.getResource(name);
                }
            };
            try (TwoPartMemoryClassLoader loader = TwoPartMemoryClassLoader.open(
                first, sha(first), second, sha(second), NAMESPACES, LIMITS, observingParent)) {
                expect(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.Missing"));
                check(loader.getResource("assets/beta/missing.txt") == null, "No fallback for owned resource");
                check(loader.loadClass(TwoPartMemoryClassLoaderTest.class.getName()) == TwoPartMemoryClassLoaderTest.class,
                    "Non-owned dependency comes from parent");
            }
        });

        run("both independently trusted SHA-256 hashes are required", () -> {
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(first, "00".repeat(32), second, sha(second), NAMESPACES, LIMITS, null));
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(first, sha(first), second, "00".repeat(32), NAMESPACES, LIMITS, null));
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(first, "not a digest", second, sha(second), NAMESPACES, LIMITS, null));
            byte[] damaged = first.clone();
            damaged[damaged.length / 2] ^= 1;
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(damaged, sha(first), second, sha(second), NAMESPACES, LIMITS, null));
        });

        run("exactly two non-empty ordinary archives", () -> {
            expect(IOException.class, () -> open(new byte[0], second));
            expect(IOException.class, () -> open(first, new byte[0]));
            expect(IOException.class, () -> open(jar(Map.of()), second));
            expect(IOException.class, () -> open(utf8("not a ZIP"), second));
            expect(IOException.class, () -> open(Arrays.copyOf(first, first.length - 1), second));
            byte[] appended = Arrays.copyOf(first, first.length + 1);
            expect(IOException.class, () -> open(appended, second));
            expect(NullPointerException.class, () -> TwoPartMemoryClassLoader.open(first, sha(first), null, sha(second), NAMESPACES, LIMITS, null));
        });

        run("duplicates across parts, within a part, and manifest collisions", () -> {
            byte[] duplicateAcrossParts = jar(Map.of("assets/beta/left.txt", utf8("different content")));
            expect(IOException.class, () -> open(first, duplicateAcrossParts));
            byte[] samePart = jar(Map.of("assets/beta/one.txt", utf8("one"), "assets/beta/two.txt", utf8("two")));
            renameSameLength(samePart, "assets/beta/two.txt", "assets/beta/one.txt");
            expect(IOException.class, () -> open(samePart, second));
            byte[] manifestA = jar(Map.of("META-INF/MANIFEST.MF", utf8("Manifest-Version: 1.0\r\n\r\n")));
            byte[] manifestB = manifestA.clone();
            var meta = new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("META-INF/"));
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(manifestA, sha(manifestA), manifestB, sha(manifestB), meta, LIMITS, null));
        });

        run("central directory corruption and local/central disagreement", () -> {
            byte[] missingDirectory = first.clone();
            int central = findSignature(missingDirectory, new byte[] {0x50, 0x4b, 0x01, 0x02});
            missingDirectory[central] = 0;
            expect(IOException.class, () -> open(missingDirectory, second));
            byte[] mismatchedSize = first.clone();
            mismatchedSize[central + 24] ^= 1;
            expect(IOException.class, () -> open(mismatchedSize, second));
            byte[] mismatchedName = jar(Map.of("assets/beta/one.txt", utf8("ok")));
            int nameOffset = findSignature(mismatchedName, new byte[] {0x50, 0x4b, 0x01, 0x02}) + 46;
            System.arraycopy(utf8("assets/beta/two.txt"), 0, mismatchedName, nameOffset, 19);
            expect(IOException.class, () -> open(mismatchedName, second));
            byte[] aliasedOffsets = jar(Map.of("assets/beta/one.txt", utf8("one"), "assets/beta/two.txt", utf8("two")));
            byte[] signature = {0x50, 0x4b, 0x01, 0x02};
            int firstCentral = findSignature(aliasedOffsets, signature);
            int secondCentral = firstCentral + 4 + findSignature(
                Arrays.copyOfRange(aliasedOffsets, firstCentral + 4, aliasedOffsets.length), signature);
            Arrays.fill(aliasedOffsets, firstCentral + 42, firstCentral + 46, (byte) 0);
            Arrays.fill(aliasedOffsets, secondCentral + 42, secondCentral + 46, (byte) 0);
            expect(IOException.class, () -> open(aliasedOffsets, second));
        });

        run("archive, entry, aggregate expansion, and entry-count limits", () -> {
            byte[] large = jar(Map.of("assets/beta/large.bin", new byte[8193]));
            byte[] small = jar(Map.of("assets/beta/small.bin", new byte[8]));
            expect(IOException.class, () -> open(first, second, new TwoPartMemoryClassLoader.Limits(10, 100, 100_000, 500_000)));
            expect(IOException.class, () -> open(large, small, new TwoPartMemoryClassLoader.Limits(1_000_000, 100, 8192, 500_000)));
            expect(IOException.class, () -> open(large, small, new TwoPartMemoryClassLoader.Limits(1_000_000, 100, 100_000, 8200)));
            expect(IOException.class, () -> open(first, second, new TwoPartMemoryClassLoader.Limits(1_000_000, 4, 100_000, 500_000)));
            try (TwoPartMemoryClassLoader loader = open(large, small, new TwoPartMemoryClassLoader.Limits(1_000_000, 2, 8193, 8201))) {
                check(loader.retainedByteCount() == 8201, "Exact limits succeed");
            }
            expect(IllegalArgumentException.class, () -> new TwoPartMemoryClassLoader.Limits(1, 0, 1, 1));
        });

        run("traversal, absolute, malformed, and disallowed namespace paths", () -> {
            for (String path : List.of("../escape", "/absolute", "assets/beta/../escape", "assets/beta/./dot",
                "assets/beta//double", "assets\\beta\\windows", "C:/absolute", "assets/beta/line\nfeed",
                "outside/no.txt", "outside/No.class", "demo/payload/../Bad.class", "demo.payload/Right.class",
                "demo/payload/Right.Extra.class")) {
                byte[] bad = jar(Map.of(path, utf8("x")));
                expect(IOException.class, () -> open(bad, second));
            }
            expect(IllegalArgumentException.class, () -> new TwoPartMemoryClassLoader.Namespaces(Set.of("java."), Set.of()));
            expect(IllegalArgumentException.class, () -> new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload"), Set.of()));
            expect(IllegalArgumentException.class, () -> new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("../")));
        });

        run("directory entries are validated and counted", () -> {
            byte[] directory = jar(Map.of("assets/beta/folder/", new byte[0], "assets/beta/folder/file.txt", utf8("ok")));
            try (TwoPartMemoryClassLoader loader = open(directory, second)) {
                check(loader.getResource("assets/beta/folder/") == null, "Directories are not payload resources");
            }
            expect(IOException.class, () -> open(jar(Map.of("assets/beta/dir/", utf8("not empty"))), second));
            byte[] duplicates = jar(Map.of("assets/beta/item/", new byte[0], "assets/beta/item", utf8("file")));
            expect(IOException.class, () -> open(duplicates, second));
        });

        run("unsupported native and multi-release payloads are rejected", () -> {
            for (String name : List.of("assets/beta/a.dll", "assets/beta/a.DYLIB", "assets/beta/a.so", "assets/beta/a.jnilib")) {
                expect(IOException.class, () -> open(jar(Map.of(name, utf8("no native payload"))), second));
            }
            byte[] multi = jar(Map.of("META-INF/versions/21/demo/payload/Right.class", classes.get("demo.payload.Right")));
            var meta = new TwoPartMemoryClassLoader.Namespaces(Set.of("demo.payload."), Set.of("META-INF/", "assets/beta/"));
            expect(IOException.class, () -> TwoPartMemoryClassLoader.open(multi, sha(multi), second, sha(second), meta, LIMITS, null));
        });

        run("input snapshots and URL escaping", () -> {
            byte[] snapshot = first.clone();
            byte[] spaced = jar(Map.of("assets/beta/a #?%.txt", utf8("encoded URL")));
            try (TwoPartMemoryClassLoader loader = open(snapshot, spaced)) {
                Arrays.fill(snapshot, (byte) 0);
                check(read(loader.getResourceAsStream("assets/beta/left.txt")).equals("left resource"), "Copied caller arrays");
                URL resource = loader.getResource("assets/beta/a #?%.txt");
                check(resource != null && resource.getRef() == null && resource.getQuery() == null, "Special characters escaped");
                check(read(resource.openStream()).equals("encoded URL"), "Escaped URL reads exact resource");
            }
        });

        run("close zeroes owned bytes and revokes streams, URLs, and loads", () -> {
            TwoPartMemoryClassLoader loader = open(first, second);
            Class<?> alreadyLoaded = loader.loadClass("demo.payload.Right");
            var resolvedMethod = alreadyLoaded.getMethod("value");
            check(resolvedMethod.invoke(null).equals("right"), "Resolve the existing method before close");
            List<byte[]> owned = ownedArrays(loader);
            InputStream issued = loader.getResourceAsStream("assets/beta/left.txt");
            URL issuedUrl = loader.getResource("assets/beta/right.txt");
            URLConnection connectedBeforeClose = issuedUrl.openConnection();
            connectedBeforeClose.connect();
            check(issued.read() == 'l', "Issued stream starts readable");
            loader.close();
            loader.close();
            check(loader.isClosed() && loader.retainedEntryCount() == 0 && loader.retainedByteCount() == 0, "Closed accounting");
            for (byte[] bytes : owned) for (byte value : bytes) check(value == 0, "Owned arrays wiped, not only dereferenced");
            expect(IOException.class, issued::read);
            expect(IOException.class, issued::available);
            expect(IOException.class, issued::readAllBytes);
            expect(IOException.class, () -> issued.read(new byte[8]));
            expect(IOException.class, () -> issued.skip(1));
            expect(IOException.class, issuedUrl::openStream);
            expect(IOException.class, connectedBeforeClose::getInputStream);
            expect(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.Late"));
            expect(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.Right"));
            expect(ClassNotFoundException.class, () -> loader.loadClass("java.lang.String"));
            check(loader.getResource("assets/beta/right.txt") == null, "No resources after close");
            check(loader.getResourceAsStream("assets/beta/right.txt") == null, "No streams after close");
            check(!loader.getResources("assets/beta/right.txt").hasMoreElements(), "No enumeration after close");
            // Important caveat: closing a ClassLoader does not erase already-defined JVM classes.
            check(resolvedMethod.invoke(null).equals("right"), "Existing resolved Class reference remains usable");
        });

        run("close does not revoke parent-owned resources", () -> {
            URL parentUrl = URL.of(URI.create("parentmemory:/parent/read.txt"), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() { connected = true; }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(utf8("parent data")); }
                    };
                }
            });
            ClassLoader parent = new ClassLoader(null) {
                @Override public URL getResource(String name) { return name.equals("parent/read.txt") ? parentUrl : null; }
            };
            TwoPartMemoryClassLoader loader = TwoPartMemoryClassLoader.open(first, sha(first), second, sha(second), NAMESPACES, LIMITS, parent);
            InputStream borrowed = loader.getResourceAsStream("parent/read.txt");
            loader.close();
            check(read(borrowed).equals("parent data"), "Parent stream ownership is independent");
            check(loader.getResource("parent/read.txt") == null, "No new parent resource lookup after close");
        });

        run("individually closed streams and concurrent close/read", () -> {
            TwoPartMemoryClassLoader loader = open(first, second);
            try {
                InputStream stream = loader.getResourceAsStream("assets/beta/left.txt");
                stream.close();
                expect(IOException.class, stream::read);
                check(loader.loadClass("demo.payload.Right") != null, "Stream close does not close loader");
                CountDownLatch closed = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                InputStream shared = loader.getResourceAsStream("assets/beta/right.txt");
                Thread reader = Thread.ofPlatform().start(() -> {
                    try {
                        closed.await();
                        expect(IOException.class, shared::read);
                    } catch (Throwable thrown) { failure.set(thrown); }
                });
                loader.close();
                closed.countDown();
                reader.join(5000);
                check(!reader.isAlive() && failure.get() == null, "Concurrent reader sees closed state");
            } finally {
                loader.close();
            }
        });

        run("overlapping resource/class access and close", () -> {
            TwoPartMemoryClassLoader loader = open(first, second);
            InputStream stream = loader.getResourceAsStream("assets/beta/left.txt");
            CountDownLatch ready = new CountDownLatch(1);
            AtomicBoolean finished = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    ready.countDown();
                    while (!finished.get()) {
                        try { stream.read(new byte[1]); } catch (IOException closed) { break; }
                        try { loader.loadClass("demo.payload.Right"); } catch (ClassNotFoundException closed) { break; }
                    }
                } catch (Throwable thrown) { failure.set(thrown); }
            });
            try {
                ready.await();
                loader.close();
                finished.set(true);
                worker.join(5000);
                check(!worker.isAlive() && failure.get() == null, "Concurrent operations either complete or report closed");
                expect(IOException.class, stream::read);
                expect(ClassNotFoundException.class, () -> loader.loadClass("demo.payload.Right"));
            } finally {
                finished.set(true);
                loader.close();
            }
        });
        System.out.println("PASS: " + passed + " focused memory-runtime checks on " + Runtime.version());
    }

    private static TwoPartMemoryClassLoader open(byte[] first, byte[] second) throws Exception {
        return open(first, second, LIMITS);
    }

    private static TwoPartMemoryClassLoader open(byte[] first, byte[] second, TwoPartMemoryClassLoader.Limits limits) throws Exception {
        return TwoPartMemoryClassLoader.open(first, sha(first), second, sha(second), NAMESPACES, limits,
            TwoPartMemoryClassLoaderTest.class.getClassLoader());
    }

    private static String read(InputStream stream) throws IOException {
        check(stream != null, "Expected stream");
        try (stream) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    @SuppressWarnings("deprecation") // Java 21 has no non-deprecated relative-URL API that preserves a private handler.
    private static URL relativeUrl(URL context, String relative) throws IOException { return new URL(context, relative); }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Shared synthetic in-memory JAR fixture builder for focused integration harnesses. */
    public static byte[] jar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                ZipEntry descriptor = new ZipEntry(entry.getKey());
                descriptor.setTime(0);
                zip.putNextEntry(descriptor);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void renameSameLength(byte[] zip, String oldName, String newName) {
        byte[] oldBytes = utf8(oldName);
        byte[] newBytes = utf8(newName);
        check(oldBytes.length == newBytes.length, "Fixture rename needs equal length");
        int replacements = 0;
        for (int i = 0; i <= zip.length - oldBytes.length; i++) {
            if (Arrays.equals(zip, i, i + oldBytes.length, oldBytes, 0, oldBytes.length)) {
                System.arraycopy(newBytes, 0, zip, i, newBytes.length);
                replacements++;
            }
        }
        check(replacements == 2, "Renamed local and central fixture entry names");
    }

    private static int findSignature(byte[] bytes, byte[] signature) {
        for (int i = 0; i <= bytes.length - signature.length; i++) {
            if (Arrays.equals(bytes, i, i + signature.length, signature, 0, signature.length)) return i;
        }
        throw new AssertionError("Expected ZIP fixture signature");
    }

    @SuppressWarnings("unchecked")
    private static List<byte[]> ownedArrays(TwoPartMemoryClassLoader loader) throws Exception {
        Field field = TwoPartMemoryClassLoader.class.getDeclaredField("entries");
        field.setAccessible(true); // Test-only direct evidence of zeroing without exposing bytes in the public API.
        return new ArrayList<>(((Map<String, byte[]>) field.get(loader)).values());
    }

    /** Shared in-memory fixture compiler; never writes generated source/class files. */
    public static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "Full JDK 21 (not only a JRE) is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> output = new LinkedHashMap<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             JavaFileManager manager = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                 @Override
                 public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                     return new SimpleJavaFileObject(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                         @Override
                         public OutputStream openOutputStream() {
                             ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                             output.put(className, bytes);
                             return bytes;
                         }
                     };
                 }
             }) {
            List<JavaFileObject> inputs = new ArrayList<>();
            sources.forEach((name, source) -> inputs.add(new SimpleJavaFileObject(
                URI.create("mem:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
            }));
            boolean ok = compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-proc:none"), null, inputs).call();
            check(ok, "In-memory fixture compilation failed: " + diagnostics.getDiagnostics());
        }
        Map<String, byte[]> result = new LinkedHashMap<>();
        output.forEach((name, bytes) -> result.put(name, bytes.toByteArray()));
        return result;
    }

    private static void run(String description, CheckedAction test) throws Exception {
        test.run();
        passed++;
        System.out.println("PASS " + description);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return;
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + thrown, thrown);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }
}
