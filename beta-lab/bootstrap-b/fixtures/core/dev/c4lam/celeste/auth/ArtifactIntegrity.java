package dev.c4lam.celeste.auth;

import dev.c4lam.celeste.CelesteClient;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final int MEMORY_PARTS = 2;
    // Release-sized bounds, deliberately larger than the small feasibility-loader defaults.
    private static final int MAX_MEMORY_ENTRIES = 65_536;
    private static final int MAX_MEMORY_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_MEMORY_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final int MAX_MEMORY_NAME_CHARS = 1024;
    private static VerifiedMemorySource memorySource;
    private static boolean sourcePinned;

    /**
     * A trusted lifecycle-owned source of the EXACT retained bytes used to load
     * the two payload parts. The lifecycle must verify both parts against trusted
     * release metadata before installation. This interface does not authenticate
     * a download, authorize a session, or accept a precomputed artifact digest.
     *
     * Visit both complete per-part maps synchronously under the source's lifetime
     * lock. Names must be original JAR entry names, including protected metadata;
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
        try (MemorySnapshot snapshot = new MemorySnapshot()) {
            try {
                source.visitParts(snapshot::accept);
            } finally {
                snapshot.finishVisit();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            snapshot.updateDigest(digest);
            return Crypto.hex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("Could not verify the client artifact", exception);
        }
    }

    private record MemoryEntry(String name, byte[] content) {
    }

    /** Private bounded snapshots prevent sorting from racing source release. */
    private static final class MemorySnapshot implements AutoCloseable {
        private final List<MemoryEntry> entries = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private int parts;
        private int entryCount;
        private long totalBytes;
        private boolean visiting = true;
        private IOException failure;

        private synchronized void accept(Map<String, byte[]> part) throws IOException {
            if (!visiting) throw new IOException("Retained artifact visit is already finished");
            if (failure != null) throw failure;
            try {
                collectPart(part);
            } catch (IOException exception) {
                failure = exception;
                throw exception;
            } catch (RuntimeException exception) {
                failure = new IOException("Could not snapshot the retained artifact", exception);
                throw failure;
            }
        }

        private void collectPart(Map<String, byte[]> part) throws IOException {
            if (part == null || ++parts > MEMORY_PARTS) {
                throw new IOException("Expected exactly two retained artifact parts");
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
                if (content.length > MAX_MEMORY_ENTRY_BYTES
                    || totalBytes > MAX_MEMORY_TOTAL_BYTES - content.length) {
                    throw new IOException("Retained artifact exceeds byte limit");
                }
                totalBytes += content.length;
                // JarEntry.isDirectory() has the same trailing-slash semantics.
                if (name.endsWith("/") || !protectedEntry(name)) continue;
                if (!names.add(name)) {
                    throw new IOException("Duplicate protected payload entry: " + name);
                }
                entries.add(new MemoryEntry(name, content.clone()));
            }
        }

        private synchronized void finishVisit() {
            visiting = false;
        }

        private synchronized void updateDigest(MessageDigest digest) throws IOException {
            if (failure != null) throw failure;
            if (parts != MEMORY_PARTS) throw new IOException("Expected exactly two retained artifact parts");
            entries.sort(Comparator.comparing(MemoryEntry::name));
            for (MemoryEntry entry : entries) {
                update(digest, entry.name(), entry.content());
            }
        }

        @Override
        public synchronized void close() {
            visiting = false;
            for (MemoryEntry entry : entries) Arrays.fill(entry.content(), (byte) 0);
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
        ByteArrayOutputStream framing = new ByteArrayOutputStream(name.length() + 16);
        try (DataOutputStream output = new DataOutputStream(framing)) {
            byte[] encodedName = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            output.writeInt(encodedName.length);
            output.write(encodedName);
            output.writeLong(content.length);
        }
        digest.update(framing.toByteArray());
        digest.update(content);
    }
}
