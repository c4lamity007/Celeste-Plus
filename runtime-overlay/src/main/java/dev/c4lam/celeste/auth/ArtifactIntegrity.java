package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.memory.NativeResourceBudget;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ArtifactIntegrity {
    private static final String MUTABLE_AUTH_RESOURCE = "META-INF/.r/4d7a9c2e";
    // Resident host transport still uses two signed shards. Independent module identities
    // are pinned by its protected catalog and verified separately by the module loader.
    private static final int RESIDENT_PARTS = 2;
    // Release-sized bounds, deliberately larger than the small feasibility-loader defaults.
    private static final int MAX_MEMORY_ENTRIES = 65_536;
    private static final int MAX_MEMORY_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_MEMORY_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int MAX_MEMORY_NAME_CHARS = 1024;
    private static VerifiedMemorySource memorySource;
    private static boolean sourcePinned;

    /**
     * A trusted lifecycle-owned source of the EXACT retained bytes used to load
     * the fixed resident runtime shards. The lifecycle must verify both against trusted
     * release metadata before installation. This interface does not authenticate
     * a download, authorize a session, or accept a precomputed artifact digest.
     *
     * Visit both complete per-part maps synchronously under the source's lifetime
     * protection: close/wipe must wait until the complete visit returns. Names
     * must be original JAR entry names, including protected metadata;
     * arrays are borrowed and must remain unchanged for the entire visit. The
     * source must throw IOException after close/revocation, even if its maps were
     * cleared. Do not retain the visitor or expose an independently mutable copy
     * that could diverge from the loader's retained bytes.
     */
    @FunctionalInterface
    public interface VerifiedMemorySource {
        void visitParts(PartVisitor visitor) throws IOException;
    }

    @FunctionalInterface
    public interface PartVisitor {
        void accept(Map<String, byte[]> entries) throws IOException;
    }

    /**
     * Install once, before authentication. There is deliberately no reset or
     * replacement API: every subsequent digest must use this source, and source
     * failures never fall back to a disk artifact. Only the trusted bootstrap
     * lifecycle may call this method; it is not a security boundary against
     * arbitrary code already executing in this JVM.
     */
    public static synchronized void installVerifiedMemorySource(VerifiedMemorySource source) {
        Objects.requireNonNull(source, "source");
        if (sourcePinned || memorySource != null) {
            throw new IllegalStateException("The artifact source is already selected");
        }
        memorySource = source;
    }

    private static synchronized VerifiedMemorySource pinSource() {
        sourcePinned = true;
        return memorySource;
    }

    static synchronized boolean hasRetainedMemorySource() {
        return memorySource != null;
    }

    private ArtifactIntegrity() {
    }

    static String digest() throws IOException {
        VerifiedMemorySource retainedSource = pinSource();
        if (retainedSource != null) {
            return digestVerifiedMemory(retainedSource);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String stagedParts = System.getProperty("celeste.payload.parts", "").strip();
            if (!stagedParts.isEmpty()) {
                List<Path> parts = java.util.Arrays.stream(stagedParts.split(
                    java.util.regex.Pattern.quote(java.io.File.pathSeparator)
                )).map(Path::of).toList();
                digestJars(parts, digest);
                return Crypto.hex(digest.digest());
            }
            Path source = Path.of(CelesteClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(source)) {
                digestDirectory(source, digest);
            } else {
                digestJar(source, digest);
            }
            return Crypto.hex(digest.digest());
        } catch (URISyntaxException | NoSuchAlgorithmException exception) {
            throw new IOException("Could not verify the client artifact", exception);
        }
    }

    /** Recomputes the existing protected-entry digest from verified retained bytes. */
    public static String digestVerifiedMemory(VerifiedMemorySource source) throws IOException {
        Objects.requireNonNull(source, "source");
        try (MemorySnapshot snapshot = new MemorySnapshot(MessageDigest.getInstance("SHA-256"))) {
            try {
                source.visitParts(snapshot::accept);
            } finally {
                snapshot.finishVisit();
            }
            return snapshot.result();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Could not verify the client artifact", exception);
        }
    }

    private record MemoryEntry(String name, byte[] content) {
    }

    /** Hash borrowed bytes before the final synchronous callback returns its lifetime borrow. */
    private static final class MemorySnapshot implements AutoCloseable {
        private final List<MemoryEntry> entries = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private final MessageDigest digest;
        private final byte[] framing = new byte[8];
        private final Thread owner = Thread.currentThread();
        private int parts;
        private int entryCount;
        private long totalBytes;
        private boolean visiting = true;
        private NativeResourceBudget nativeBudget = NativeResourceBudget.empty();
        private boolean indexSeen;
        private IOException failure;
        private String result;

        private MemorySnapshot(MessageDigest digest) { this.digest = digest; }

        private synchronized void accept(Map<String, byte[]> part) throws IOException {
            if (!visiting) throw new IOException("Retained artifact visit is already finished");
            if (failure != null) throw failure;
            try {
                if (Thread.currentThread() != owner) throw new IOException("Retained artifact visit must be synchronous");
                collectPart(part);
                if (parts == RESIDENT_PARTS) {
                    updateDigest(digest);
                    result = Crypto.hex(digest.digest());
                }
            } catch (IOException exception) {
                failure = exception;
                throw exception;
            } catch (RuntimeException exception) {
                failure = new IOException("Could not snapshot the retained artifact", exception);
                throw failure;
            } finally {
                // Only metadata survives a successful complete visit. Neither
                // hashing nor raw byte references escape the source lifetime.
                if (parts >= RESIDENT_PARTS || failure != null) entries.clear();
            }
        }

        private void collectPart(Map<String, byte[]> part) throws IOException {
            if (part == null || ++parts > RESIDENT_PARTS) {
                throw new IOException("Expected exactly two retained resident parts");
            }
            // Packaging pins this declaration to part A. Inspect it before native
            // snapshots are allocated, while retaining the original one-visit ABI.
            // The lifecycle has already authenticated these exact two outer parts.
            if (parts == 1 && part.containsKey(NativeResourceBudget.RESOURCE)) {
                nativeBudget = NativeResourceBudget.parseTrusted(part.get(NativeResourceBudget.RESOURCE));
            } else if (part.containsKey(NativeResourceBudget.RESOURCE)) {
                throw new IOException("Native budget must be in the first retained part");
            }
            for (Map.Entry<String, byte[]> entry : part.entrySet()) {
                if (++entryCount > MAX_MEMORY_ENTRIES) {
                    throw new IOException("Retained artifact exceeds entry limit");
                }
                if (entry == null) throw new IOException("Missing retained artifact entry");
                String name = entry.getKey();
                byte[] content = entry.getValue();
                if (name == null || name.isEmpty() || name.length() > MAX_MEMORY_NAME_CHARS || content == null) {
                    throw new IOException("Invalid retained artifact entry");
                }
                int entryLimit = nativeBudget.entryLimit(name, content.length, MAX_MEMORY_ENTRY_BYTES);
                if (content.length > entryLimit
                    || totalBytes > MAX_MEMORY_TOTAL_BYTES - content.length) {
                    throw new IOException("Retained artifact exceeds byte limit");
                }
                totalBytes += content.length;
                if (name.equals(NativeResourceBudget.RESOURCE)) {
                    if (indexSeen) throw new IOException("Duplicate retained native index");
                    nativeBudget.verifyIndex(content);
                    indexSeen = true;
                }
                nativeBudget.verify(name, content);
                // JarEntry.isDirectory() has the same trailing-slash semantics.
                if (name.endsWith("/") || !protectedEntry(name)) continue;
                if (!names.add(name)) {
                    throw new IOException("Duplicate protected payload entry: " + name);
                }
                entries.add(new MemoryEntry(name, content));
            }
        }

        private synchronized void finishVisit() {
            visiting = false;
            entries.clear();
        }

        private synchronized String result() throws IOException {
            if (failure != null) throw failure;
            if (parts != RESIDENT_PARTS || result == null) throw new IOException("Expected exactly two retained resident parts");
            return result;
        }

        private synchronized void updateDigest(MessageDigest digest) throws IOException {
            if (failure != null) throw failure;
            if (parts != RESIDENT_PARTS) throw new IOException("Expected exactly two retained resident parts");
            if (!indexSeen) nativeBudget.verifyIndex(null);
            nativeBudget.requireComplete(names);
            entries.sort(Comparator.comparing(MemoryEntry::name));
            for (MemoryEntry entry : entries) {
                update(digest, entry.name(), entry.content(), framing);
            }
        }

        @Override
        public synchronized void close() {
            visiting = false;
            entries.clear();
            names.clear();
        }
    }

    private static void digestJar(Path source, MessageDigest digest) throws IOException {
        try (JarFile jar = new JarFile(source.toFile())) {
            List<JarEntry> entries = jar.stream()
                .filter(entry -> !entry.isDirectory() && protectedEntry(entry.getName()))
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
            for (JarEntry entry : entries) {
                try (InputStream input = jar.getInputStream(entry)) {
                    update(digest, entry.getName(), input.readAllBytes());
                }
            }
        }
    }

    private static void digestJars(List<Path> sources, MessageDigest digest) throws IOException {
        record StagedEntry(Path source, String name) {
        }
        List<StagedEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path source : sources) {
            try (JarFile jar = new JarFile(source.toFile())) {
                jar.stream()
                    .filter(entry -> !entry.isDirectory() && protectedEntry(entry.getName()))
                    .forEach(entry -> {
                        if (!names.add(entry.getName())) {
                            throw new IllegalStateException("Duplicate protected payload entry: " + entry.getName());
                        }
                        entries.add(new StagedEntry(source, entry.getName()));
                    });
            }
        }
        entries.sort(Comparator.comparing(StagedEntry::name));
        for (StagedEntry entry : entries) {
            try (JarFile jar = new JarFile(entry.source().toFile());
                 InputStream input = jar.getInputStream(jar.getJarEntry(entry.name()))) {
                update(digest, entry.name(), input.readAllBytes());
            }
        }
    }

    private static void digestDirectory(Path source, MessageDigest digest) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.walk(source)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> protectedEntry(source.relativize(path).toString().replace('\\', '/')))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(path -> source.relativize(path).toString().replace('\\', '/')));
        for (Path path : files) {
            update(digest, source.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
    }

    private static boolean protectedEntry(String name) {
        // Class/package and asset names are randomized after compilation, so a
        // source-package allow-list would silently stop protecting most of a
        // release. The packed release descriptor is the sole exclusion because
        // the packager writes the digest into it after this digest is made.
        return !name.equals(MUTABLE_AUTH_RESOURCE);
    }

    private static void update(MessageDigest digest, String name, byte[] content) throws IOException {
        update(digest, name, content, new byte[8]);
    }

    private static void update(MessageDigest digest, String name, byte[] content, byte[] framing) {
        byte[] encodedName = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long length = encodedName.length;
        for (int index = 3; index >= 0; index--, length >>>= 8) framing[index] = (byte) length;
        digest.update(framing, 0, 4);
        digest.update(encodedName);
        length = content.length;
        for (int index = 7; index >= 0; index--, length >>>= 8) framing[index] = (byte) length;
        digest.update(framing, 0, 8);
        digest.update(content);
    }
}
