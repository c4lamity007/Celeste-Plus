package dev.c4lam.celeste.beta.delivery;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.stream.Stream;

/** Ciphertext only. Cache presence is never evidence of authorization. */
public final class ModuleCiphertextCache {
    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    private final Path directory;
    public ModuleCiphertextCache(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        // Optional disk storage must not prevent construction of the authenticated
        // client. Validate actual accesses below; failed reads/writes use memory.
    }
    private static void checkParents(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Module cache cannot follow symbolic links");
    }
    private Path file(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cache digest");
        return directory.resolve(digest + ".cstm");
    }
    public synchronized byte[] read(String digest, int size) throws IOException {
        if (size <= 0 || size > ModuleContainer.MAX_BYTES) throw new IOException("Invalid module ciphertext size");
        checkParents(directory);
        Path file = file(digest);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != size) return null;
        byte[] bytes = new byte[size];
        boolean accepted = false;
        try {
            try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                // The authenticated size is known. Avoid JDK chunk buffers and a
                // second full copy on every warm activation; still reject changes
                // between the file-size check and this bounded read.
                if (stream.readNBytes(bytes, 0, size) != size || stream.read() != -1) return null;
            }
            if (!hash(bytes).equals(digest)) return null;
            accepted = true;
            return bytes;
        } finally {
            if (!accepted) Arrays.fill(bytes, (byte)0);
        }
    }
    public synchronized void put(String digest, byte[] ciphertext) throws IOException {
        if (ciphertext.length == 0 || ciphertext.length > ModuleContainer.MAX_BYTES || !hash(ciphertext).equals(digest))
            throw new IOException("Invalid module ciphertext");
        checkParents(directory);
        Files.createDirectories(directory);
        checkParents(directory);
        Path temporary = Files.createTempFile(directory, "module-", ".tmp");
        try {
            Files.write(temporary, ciphertext, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            try { Files.move(temporary, file(digest), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file(digest), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        trim(file(digest));
    }
    private void trim(Path keep) throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            var files = paths.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.cstm"))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparingLong(ModuleCiphertextCache::modified)).toList();
            long total = 0;
            for (Path p : files) total += Files.size(p);
            for (Path p : files) {
                if (total <= MAX_CACHE_BYTES) break;
                if (!p.equals(keep)) { long size = Files.size(p); Files.deleteIfExists(p); total -= size; }
            }
        }
    }
    private static long modified(Path path) {
        try { return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(); }
        catch (IOException failure) { return Long.MIN_VALUE; }
    }
    private static String hash(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
    }
}
