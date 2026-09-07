package dev.c4lam.celeste.beta.memory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 21 feasibility runtime for two disjoint, already-authorized in-memory JARs.
 * This is a class-loading boundary, NOT a security sandbox. Only load trusted code.
 * It does not fetch, persist, attach, instrument, load natives, or integrate Fabric.
 */
public final class TwoPartMemoryClassLoader extends ClassLoader implements AutoCloseable {
    /** Borrowed entry arrays must not be changed or retained beyond this synchronous callback. */
    @FunctionalInterface
    public interface RetainedPartVisitor {
        void accept(Map<String, byte[]> entries) throws IOException;
    }

    /** Limits cover both parts together except maxPartBytes and maxEntryBytes. */
    public record Limits(int maxPartBytes, int maxEntries, int maxEntryBytes, long maxTotalBytes) {
        public Limits {
            if (maxPartBytes < 1 || maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < 1) {
                throw new IllegalArgumentException("All limits must be positive");
            }
        }

        public static Limits betaDefaults() {
            return new Limits(16 * 1024 * 1024, 10_000, 8 * 1024 * 1024, 64L * 1024 * 1024);
        }
    }

    /**
     * Class prefixes must end in '.', resource prefixes in '/'. Classes outside
     * classPrefixes are parent-only. Resources inside either owned namespace
     * are payload-only; all other resource lookups are parent-only.
     */
    public record Namespaces(Set<String> classPrefixes, Set<String> resourcePrefixes) {
        public Namespaces {
            classPrefixes = Set.copyOf(classPrefixes);
            resourcePrefixes = Set.copyOf(resourcePrefixes);
            if (classPrefixes.isEmpty()) {
                throw new IllegalArgumentException("At least one class package prefix is required");
            }
            for (String prefix : classPrefixes) {
                if (!prefix.endsWith(".") || !isBinaryName(prefix.substring(0, prefix.length() - 1))) {
                    throw new IllegalArgumentException("Invalid class package prefix: " + prefix);
                }
                for (String reserved : Set.of("java.", "javax.", "jdk.", "sun.", "com.sun.")) {
                    if (prefix.startsWith(reserved) || reserved.startsWith(prefix)) {
                        throw new IllegalArgumentException("Reserved class namespace: " + prefix);
                    }
                }
            }
            for (String prefix : resourcePrefixes) {
                if (!prefix.endsWith("/") || !isCanonicalPath(prefix, true)) {
                    throw new IllegalArgumentException("Invalid resource prefix: " + prefix);
                }
            }
        }

        private boolean ownsClass(String name) {
            return classPrefixes.stream().anyMatch(name::startsWith);
        }

        private boolean ownsResource(String name) {
            return resourcePrefixes.stream().anyMatch(name::startsWith)
                || classPrefixes.stream().map(prefix -> prefix.replace('.', '/')).anyMatch(name::startsWith);
        }
    }

    private final Namespaces namespaces;
    private final Map<String, byte[]> entries;
    private final List<Map<String, byte[]>> partEntries;
    private final List<Map<String, byte[]>> partViews;
    private final String resourceId = UUID.randomUUID().toString();
    private boolean closed;
    private boolean visitingParts;
    private long retainedBytes;

    private TwoPartMemoryClassLoader(ClassLoader parent, Namespaces namespaces, Map<String, byte[]> entries,
                                    Map<String, byte[]> firstPart, Map<String, byte[]> secondPart) {
        super("celeste-private-beta-memory", parent);
        this.namespaces = namespaces;
        this.entries = entries;
        this.partEntries = List.of(firstPart, secondPart);
        this.partViews = List.of(Collections.unmodifiableMap(firstPart), Collections.unmodifiableMap(secondPart));
        this.retainedBytes = entries.values().stream().mapToLong(bytes -> bytes.length).sum();
    }

    /**
     * Creates a loader from EXACTLY two parts. Expected hashes must come from a
     * caller-trusted source, not be accepted from the same untrusted download.
     * Inputs are copied; the caller retains ownership and responsibility for its
     * own arrays. Snapshots and partial parsing buffers are wiped on failure.
     */
    public static TwoPartMemoryClassLoader open(
        byte[] firstJar, String firstTrustedSha256,
        byte[] secondJar, String secondTrustedSha256,
        Namespaces namespaces, Limits limits, ClassLoader parent
    ) throws IOException {
        Objects.requireNonNull(firstJar, "firstJar");
        Objects.requireNonNull(secondJar, "secondJar");
        Objects.requireNonNull(namespaces, "namespaces");
        Objects.requireNonNull(limits, "limits");
        checkPartSize(firstJar, limits);
        checkPartSize(secondJar, limits);
        byte[] firstSnapshot = firstJar.clone();
        byte[] secondSnapshot = null;
        Map<String, byte[]> parsed = new HashMap<>();
        Map<String, byte[]> firstPart = new HashMap<>();
        Map<String, byte[]> secondPart = new HashMap<>();
        boolean transferred = false;
        try {
            secondSnapshot = secondJar.clone();
            verifyDigest(firstSnapshot, firstTrustedSha256, "first");
            verifyDigest(secondSnapshot, secondTrustedSha256, "second");
            ParseBudget budget = new ParseBudget(limits);
            parsePart(firstSnapshot, "first", namespaces, budget, parsed, firstPart);
            parsePart(secondSnapshot, "second", namespaces, budget, parsed, secondPart);
            TwoPartMemoryClassLoader result = new TwoPartMemoryClassLoader(parent, namespaces, parsed, firstPart, secondPart);
            transferred = true;
            return result;
        } finally {
            Arrays.fill(firstSnapshot, (byte) 0);
            if (secondSnapshot != null) Arrays.fill(secondSnapshot, (byte) 0);
            if (!transferred) {
                wipeEntries(parsed);
                firstPart.clear();
                secondPart.clear();
            }
        }
    }

    private static void checkPartSize(byte[] jar, Limits limits) throws IOException {
        if (jar.length == 0 || jar.length > limits.maxPartBytes()) {
            throw new IOException("JAR part is empty or exceeds maxPartBytes");
        }
    }

    private static void verifyDigest(byte[] jar, String trustedHex, String part) throws IOException {
        if (trustedHex == null || !trustedHex.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("Expected SHA-256 must contain exactly 64 hexadecimal characters");
        }
        byte[] expected = HexFormat.of().parseHex(trustedHex);
        byte[] actual = null;
        try {
            actual = MessageDigest.getInstance("SHA-256").digest(jar);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IOException("SHA-256 mismatch for " + part + " part");
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java 21 requires SHA-256", impossible);
        } finally {
            Arrays.fill(expected, (byte) 0);
            if (actual != null) Arrays.fill(actual, (byte) 0);
        }
    }

    private static void parsePart(byte[] jar, String part, Namespaces namespaces,
                                  ParseBudget budget, Map<String, byte[]> parsed,
                                  Map<String, byte[]> retainedPart) throws IOException {
        Map<String, CentralEntry> directory = checkZipDirectory(jar, budget.limits.maxEntries() - budget.entryCount);
        int expectedEntries = directory.size();
        int localEntries = 0;
        int payloadEntries = 0;
        byte[] scratch = new byte[8192];
        // ZipInputStream deliberately enumerates the manifest as well, unlike JarInputStream.
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                localEntries++;
                if (++budget.entryCount > budget.limits.maxEntries()) {
                    throw new IOException("Archive exceeds maxEntries");
                }
                String name = entry.getName();
                if (!isCanonicalPath(name, entry.isDirectory())) {
                    throw new IOException("Non-canonical JAR entry path: " + name);
                }
                CentralEntry central = directory.remove(name);
                if (central == null) throw new IOException("Entry missing from ZIP central directory: " + name);
                String identity = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (!budget.names.add(identity)) {
                    throw new IOException("Duplicate entry across/in parts: " + name);
                }
                if (entry.isDirectory()) {
                    if (zip.read() != -1) throw new IOException("Directory entry contains data: " + name);
                    zip.closeEntry();
                    central.verify(entry);
                    byte[] content = new byte[0];
                    parsed.put(name, content);
                    retainedPart.put(name, content);
                    continue;
                }
                validatePayloadName(name, namespaces);
                if (entry.getSize() > budget.limits.maxEntryBytes()) {
                    throw new IOException("Entry exceeds maxEntryBytes: " + name);
                }
                try (WipingBuffer buffer = new WipingBuffer(budget.limits.maxEntryBytes())) {
                    int count;
                    while ((count = zip.read(scratch)) != -1) {
                        if (budget.totalBytes > budget.limits.maxTotalBytes() - count) {
                            throw new IOException("Archive exceeds maxTotalBytes");
                        }
                        buffer.append(scratch, count);
                        budget.totalBytes += count;
                    }
                    zip.closeEntry(); // also validates CRC/size for streamed entries
                    central.verify(entry);
                    byte[] content = buffer.copy();
                    parsed.put(name, content);
                    retainedPart.put(name, content);
                }
                payloadEntries++;
            }
        } catch (IllegalArgumentException malformedZip) {
            throw new IOException("Invalid ZIP encoding in " + part + " part", malformedZip);
        } finally {
            Arrays.fill(scratch, (byte) 0);
        }
        if (localEntries != expectedEntries || !directory.isEmpty() || payloadEntries == 0) {
            throw new IOException("Invalid or empty " + part + " JAR part");
        }
    }

    // Reject truncated/concatenated archives, split disks and ZIP64 in this small beta.
    // Central names and metadata must agree with the local entries consumed by
    // ZipInputStream. This is not a signed-JAR verifier or a ZIP repair tool.
    private static Map<String, CentralEntry> checkZipDirectory(byte[] jar, int remainingEntries) throws IOException {
        if (jar.length < 22 || u32(jar, 0) != 0x04034b50L) {
            throw new IOException("Expected a non-empty ordinary ZIP/JAR");
        }
        for (int end = jar.length - 22; end >= Math.max(0, jar.length - 65_557); end--) {
            if (u32(jar, end) != 0x06054b50L || end + 22 + u16(jar, end + 20) != jar.length) continue;
            int count = u16(jar, end + 10);
            if (u16(jar, end + 4) != 0 || u16(jar, end + 6) != 0
                || count == 0 || count == 0xffff || count != u16(jar, end + 8)
                || u32(jar, end + 12) + u32(jar, end + 16) != end) {
                throw new IOException("Unsupported or inconsistent ZIP directory");
            }
            if (count > remainingEntries) throw new IOException("Archive exceeds maxEntries");
            long offset = u32(jar, end + 16);
            if (offset > end) throw new IOException("Invalid central directory offset");
            int cursor = (int) offset;
            Map<String, CentralEntry> directory = new HashMap<>();
            Set<Long> localOffsets = new HashSet<>();
            for (int i = 0; i < count; i++) {
                if (cursor > end - 46 || u32(jar, cursor) != 0x02014b50L) {
                    throw new IOException("Missing or truncated central directory record");
                }
                int nameLength = u16(jar, cursor + 28);
                int extraLength = u16(jar, cursor + 30);
                int commentLength = u16(jar, cursor + 32);
                long next = (long) cursor + 46 + nameLength + extraLength + commentLength;
                if (next > end || u16(jar, cursor + 34) != 0) {
                    throw new IOException("Truncated or split central directory entry");
                }
                String name = StandardCharsets.UTF_8.newDecoder().decode(
                    ByteBuffer.wrap(jar, cursor + 46, nameLength)).toString();
                if (!isCanonicalPath(name, name.endsWith("/"))) {
                    throw new IOException("Non-canonical central directory path: " + name);
                }
                long localOffset = u32(jar, cursor + 42);
                if (localOffset > offset - 30 || u32(jar, (int) localOffset) != 0x04034b50L) {
                    throw new IOException("Invalid local entry offset: " + name);
                }
                int local = (int) localOffset;
                int localNameLength = u16(jar, local + 26);
                long localDataOffset = localOffset + 30 + localNameLength + u16(jar, local + 28);
                if (localDataOffset > offset || !localOffsets.add(localOffset)) {
                    throw new IOException("Invalid or duplicate local entry offset: " + name);
                }
                String localName = StandardCharsets.UTF_8.newDecoder().decode(
                    ByteBuffer.wrap(jar, local + 30, localNameLength)).toString();
                if (!name.equals(localName)) throw new IOException("Central offset points to a different entry: " + name);
                int method = u16(jar, cursor + 10);
                long size = u32(jar, cursor + 24);
                long compressedSize = u32(jar, cursor + 20);
                if (localDataOffset + compressedSize > offset || u16(jar, local + 8) != method
                    || u16(jar, local + 6) != u16(jar, cursor + 8)) {
                    throw new IOException("Local/central header differs or overlaps directory: " + name);
                }
                if ((u16(jar, cursor + 8) & 0x41) != 0 || size == 0xffffffffL || compressedSize == 0xffffffffL
                    || (method != ZipEntry.STORED && method != ZipEntry.DEFLATED)) {
                    throw new IOException("Unsupported encrypted/ZIP64/compression entry: " + name);
                }
                CentralEntry previous = directory.put(name,
                    new CentralEntry(method, u32(jar, cursor + 16), compressedSize, size));
                if (previous != null) throw new IOException("Duplicate central directory entry: " + name);
                cursor = (int) next;
            }
            if (cursor != end) throw new IOException("Unexpected data in ZIP central directory");
            return directory;
        }
        throw new IOException("Missing ZIP end directory or unexpected trailing bytes");
    }

    private record CentralEntry(int method, long crc, long compressedSize, long size) {
        private void verify(ZipEntry entry) throws IOException {
            if (entry.getMethod() != method || entry.getCrc() != crc
                || entry.getCompressedSize() != compressedSize || entry.getSize() != size) {
                throw new IOException("Local/central ZIP metadata differs: " + entry.getName());
            }
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(u16(bytes, offset) | (u16(bytes, offset + 2) << 16));
    }

    private static void validatePayloadName(String name, Namespaces namespaces) throws IOException {
        if (name.startsWith("META-INF/versions/") || name.equals("module-info.class")
            || name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(dll|so|dylib|jnilib)$")) {
            throw new IOException("Unsupported multi-release/module/native entry: " + name);
        }
        if (name.endsWith(".class")) {
            String binaryName = name.substring(0, name.length() - 6).replace('/', '.');
            if (!isClassBinaryName(binaryName) || !namespaces.ownsClass(binaryName)
                || !(binaryName.replace('.', '/') + ".class").equals(name)) {
                throw new IOException("Class outside approved namespace: " + name);
            }
        } else if (!namespaces.ownsResource(name)) {
            throw new IOException("Resource outside approved namespace: " + name);
        }
    }

    private static boolean isBinaryName(String name) {
        if (name.isEmpty()) return false;
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) return false;
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) return false;
            }
        }
        return true;
    }

    private static boolean isClassBinaryName(String name) {
        int lastDot = name.lastIndexOf('.');
        if (lastDot > 0 && name.substring(lastDot + 1).equals("package-info")) {
            return isBinaryName(name.substring(0, lastDot));
        }
        return isBinaryName(name);
    }

    private static boolean isCanonicalPath(String name, boolean directory) {
        if (name == null || name.isEmpty() || name.length() > 1024 || name.startsWith("/")
            || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.endsWith("/") != directory) return false;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) return false;
        }
        String path = directory ? name.substring(0, name.length() - 1) : name;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (closed) throw new ClassNotFoundException("Memory loader is closed: " + name);
        Class<?> result = findLoadedClass(name);
        if (result == null) {
            result = namespaces.ownsClass(name) ? findClass(name) : super.loadClass(name, false);
        }
        if (resolve) resolveClass(result);
        return result;
    }

    @Override
    protected synchronized Class<?> findClass(String name) throws ClassNotFoundException {
        if (closed || !namespaces.ownsClass(name) || !isClassBinaryName(name)) throw new ClassNotFoundException(name);
        byte[] bytes = entries.get(name.replace('.', '/') + ".class");
        if (bytes == null) throw new ClassNotFoundException(name);
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public synchronized URL getResource(String name) {
        if (closed || !isCanonicalPath(name, false)) return null;
        return namespaces.ownsResource(name) ? findResource(name) : super.getResource(name);
    }

    @Override
    public synchronized Enumeration<URL> getResources(String name) throws IOException {
        if (closed || !isCanonicalPath(name, false)) return Collections.emptyEnumeration();
        if (!namespaces.ownsResource(name)) return super.getResources(name);
        URL resource = findResource(name);
        return resource == null ? Collections.emptyEnumeration() : Collections.enumeration(Set.of(resource));
    }

    @Override
    protected synchronized URL findResource(String name) {
        if (closed || !isCanonicalPath(name, false) || !namespaces.ownsResource(name) || !entries.containsKey(name)) return null;
        try {
            String path = new URI(null, null, "/" + resourceId + "/" + name, null).toASCIIString();
            return URL.of(new URI("memoryjar:" + path), new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL url) throws IOException {
                    String requestedName = resourceNameFromUrl(url);
                    return new URLConnection(url) {
                        @Override
                        public void connect() throws IOException {
                            synchronized (TwoPartMemoryClassLoader.this) {
                                requireResource(requestedName);
                                connected = true;
                            }
                        }

                        @Override
                        public InputStream getInputStream() throws IOException {
                            connect();
                            synchronized (TwoPartMemoryClassLoader.this) {
                                requireResource(requestedName);
                                return new RevocableResourceStream(requestedName);
                            }
                        }
                    };
                }
            });
        } catch (IOException | URISyntaxException invalidUrl) {
            throw new IllegalStateException("Cannot encode canonical in-memory resource name", invalidUrl);
        }
    }

    private String resourceNameFromUrl(URL url) throws IOException {
        try {
            URI uri = url.toURI();
            String prefix = "/" + resourceId + "/";
            String path = uri.getPath();
            if (!uri.getScheme().equals("memoryjar") || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || path == null || !path.startsWith(prefix)) {
                throw new IOException("URL does not identify this memory loader");
            }
            String name = path.substring(prefix.length());
            if (!isCanonicalPath(name, false) || !namespaces.ownsResource(name)) {
                throw new IOException("Invalid in-memory resource path");
            }
            return name;
        } catch (URISyntaxException malformed) {
            throw new IOException("Malformed memory resource URL", malformed);
        }
    }

    @Override
    protected synchronized Enumeration<URL> findResources(String name) {
        URL resource = findResource(name);
        return resource == null ? Collections.emptyEnumeration() : Collections.enumeration(Set.of(resource));
    }

    @Override
    public synchronized InputStream getResourceAsStream(String name) {
        if (closed || !isCanonicalPath(name, false)) return null;
        if (!namespaces.ownsResource(name)) return super.getResourceAsStream(name);
        return entries.containsKey(name) ? new RevocableResourceStream(name) : null;
    }

    public synchronized boolean isClosed() { return closed; }
    public synchronized int retainedEntryCount() { return entries.size(); }
    public synchronized long retainedByteCount() { return retainedBytes; }

    /**
     * Visits the two ORIGINAL parts in open() argument order under the same
     * lifetime monitor used for loading, resource reads, and close. Views are
     * structurally unmodifiable and share the exact arrays used by this loader;
     * nothing is reparsed or copied. Original directory entries are included
     * with their trailing slash and empty content; map iteration order is unspecified.
     *
     * This is a trusted in-process capability, not a sandbox: callbacks must not
     * mutate/retain borrowed arrays or maps, start asynchronous reads, or wait on
     * another thread that needs this loader. A consumer needing bytes after the
     * callback must copy them during the visit and own that copy's lifecycle.
     *
     * Throws IOException after close or for a nested visit. A callback failure
     * stops the visit and propagates, with the lifetime guard released in finally.
     * Concurrent close waits for the complete visit; reentrant close from inside
     * a callback throws IllegalStateException rather than invalidating borrowed bytes.
     */
    public synchronized void visitRetainedParts(RetainedPartVisitor visitor) throws IOException {
        if (closed) throw new IOException("Memory loader is closed");
        Objects.requireNonNull(visitor, "visitor");
        if (visitingParts) throw new IOException("A retained-part visit is already active");
        visitingParts = true;
        try {
            for (Map<String, byte[]> part : partViews) visitor.accept(part);
        } finally {
            visitingParts = false;
        }
    }

    /**
     * Idempotently revokes this loader and wipes all its owned entry arrays.
     * JVM-defined classes and bytes already copied by callers remain outside its
     * control. Unloading requires dropping all references and is GC-dependent.
     */
    @Override
    public synchronized void close() {
        if (closed) return;
        if (visitingParts) throw new IllegalStateException("Cannot close during a retained-part callback");
        closed = true;
        wipeEntries(entries);
        partEntries.forEach(Map::clear);
        retainedBytes = 0;
    }

    private static void wipeEntries(Map<String, byte[]> entries) {
        entries.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        entries.clear();
    }

    private byte[] requireResource(String name) throws IOException {
        if (closed) throw new IOException("Memory loader is closed");
        byte[] bytes = entries.get(name);
        if (bytes == null) throw new IOException("Resource is unavailable: " + name);
        return bytes;
    }

    private final class RevocableResourceStream extends InputStream {
        private final String name;
        private int position;
        private boolean streamClosed;

        private RevocableResourceStream(String name) { this.name = name; }

        private byte[] data() throws IOException {
            if (streamClosed) throw new IOException("Resource stream is closed");
            return requireResource(name);
        }

        @Override
        public int read() throws IOException {
            synchronized (TwoPartMemoryClassLoader.this) {
                byte[] bytes = data();
                return position < bytes.length ? bytes[position++] & 0xff : -1;
            }
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            synchronized (TwoPartMemoryClassLoader.this) {
                byte[] bytes = data();
                if (length == 0) return 0;
                if (position == bytes.length) return -1;
                int count = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, target, offset, count);
                position += count;
                return count;
            }
        }

        @Override
        public int available() throws IOException {
            synchronized (TwoPartMemoryClassLoader.this) { return data().length - position; }
        }

        @Override
        public void close() {
            synchronized (TwoPartMemoryClassLoader.this) { streamClosed = true; }
        }
    }

    private static final class ParseBudget {
        private final Limits limits;
        private final Set<String> names = new HashSet<>();
        private int entryCount;
        private long totalBytes;
        private ParseBudget(Limits limits) { this.limits = limits; }
    }

    private static final class WipingBuffer implements AutoCloseable {
        private final int limit;
        private byte[] bytes;
        private int size;

        private WipingBuffer(int limit) {
            this.limit = limit;
            this.bytes = new byte[Math.min(8192, limit)];
        }

        private void append(byte[] source, int count) throws IOException {
            if (count > limit - size) throw new IOException("Entry exceeds maxEntryBytes");
            int required = size + count;
            if (required > bytes.length) {
                int capacity = (int) Math.min(limit, Math.max((long) required, (long) bytes.length * 2));
                byte[] replacement = Arrays.copyOf(bytes, capacity);
                Arrays.fill(bytes, (byte) 0);
                bytes = replacement;
            }
            System.arraycopy(source, 0, bytes, size, count);
            size = required;
        }

        private byte[] copy() { return Arrays.copyOf(bytes, size); }

        @Override
        public void close() { Arrays.fill(bytes, (byte) 0); size = 0; }
    }
}
