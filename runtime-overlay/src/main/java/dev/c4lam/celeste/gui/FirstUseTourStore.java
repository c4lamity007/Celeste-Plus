package dev.c4lam.celeste.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Once-per-user presentation marker; never exported into shareable configs. */
public final class FirstUseTourStore {
    private static final Set<Path> SEEN_THIS_SESSION = ConcurrentHashMap.newKeySet();
    private static final byte[] MARKER =
        "# Celeste+ first authenticated GUI opening\nguiTourSeen=true\n".getBytes(StandardCharsets.UTF_8);
    private final Path file;
    private final Path legacyInstanceFile;

    public FirstUseTourStore(Path file) { this(file, null); }

    private FirstUseTourStore(Path file, Path legacyInstanceFile) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.legacyInstanceFile = legacyInstanceFile == null ? null : legacyInstanceFile.toAbsolutePath().normalize();
    }

    public static FirstUseTourStore forUser(Path userHome, Path legacyInstanceFile) {
        return new FirstUseTourStore(Objects.requireNonNull(userHome).resolve(".celeste/ui/first-use-plus.properties"),
            legacyInstanceFile);
    }

    /** Claim before displaying anything, so interruption never makes a later injection a first use. */
    public synchronized boolean beginFirstUse() throws IOException {
        // Keep failed attempts closed for this classloader too. A different process is
        // protected by CREATE_NEW below, rather than a check-then-overwrite sequence.
        if (!SEEN_THIS_SESSION.add(file)) return false;
        try {
            if (markerExists()) return false;
            boolean legacySeen = legacySeen();
            return createMarker() && !legacySeen;
        } catch (SecurityException failure) {
            throw new IOException("Cannot claim the first-use walkthrough marker", failure);
        }
    }

    public synchronized boolean seen() throws IOException {
        if (SEEN_THIS_SESSION.contains(file)) return true;
        try {
            if (markerExists()) {
                SEEN_THIS_SESSION.add(file);
                return true;
            }
            return legacySeen();
        } catch (SecurityException failure) {
            throw new IOException("Cannot read the first-use walkthrough marker", failure);
        }
    }

    /** Manual replay, Skip and Done may reaffirm the marker, but can never reset it. */
    public synchronized void dismiss() throws IOException {
        SEEN_THIS_SESSION.add(file);
        try {
            if (!markerExists()) createMarker();
        } catch (SecurityException failure) {
            throw new IOException("Cannot save the first-use walkthrough marker", failure);
        }
    }

    private boolean markerExists() throws IOException {
        return regularFileExists(file);
    }

    private boolean legacySeen() throws IOException {
        if (legacyInstanceFile == null || !regularFileExists(legacyInstanceFile)) return false;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(legacyInstanceFile)) {
            values.load(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Cannot read the legacy walkthrough preference", malformed);
        }
        return Boolean.parseBoolean(values.getProperty("guiTourSeen", "false"));
    }

    private boolean createMarker() throws IOException {
        Files.createDirectories(file.getParent());
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)) {
            ByteBuffer bytes = ByteBuffer.wrap(MARKER);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        } catch (FileAlreadyExistsException claimedElsewhere) {
            if (!markerExists()) throw new IOException("First-use marker changed during its claim", claimedElsewhere);
            return false;
        }
        return true;
    }

    private static boolean regularFileExists(Path path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (NoSuchFileException missing) {
            return false;
        }
        if (!attributes.isRegularFile()) throw new IOException("Walkthrough preference is not a regular file: " + path);
        // The marker's existence is the claim. An interrupted write must not permit
        // another automatic tour, even if its properties were never fully written.
        return true;
    }
}
