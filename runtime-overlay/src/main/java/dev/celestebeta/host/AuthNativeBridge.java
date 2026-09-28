package dev.celestebeta.host;

import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Canonical parent-defined custody; never defines or loads payload natives.
 * expectedSha256 must originate in the already authenticated resource contract.
 * This is file ownership bookkeeping, not an authorization or same-user sandbox.
 */
public final class AuthNativeBridge {
    private static final int MAX_DLL = 64 * 1024 * 1024;
    private static final int MAX_RECEIPT = 16384;
    private static final int MAX_DIRECTORIES = 128;
    private static final String PREFIX = "auth-native-";
    private static final String DLL = "auth.dll";
    private static final String RECEIPT = "owner.cst";
    private static final String KEY = "custody.key";
    private static Custodian configured;

    private AuthNativeBridge() { }

    /** Called only by the verified, resident parent. Configuration is immutable. */
    public static synchronized void configure(Path ownedRoot) throws IOException {
        Path requested = ownedRoot.toAbsolutePath().normalize();
        if (configured != null) {
            if (!configured.root.equals(requested)) throw new IOException("Custody root is already configured");
            return;
        }
        configured = new Custodian(requested, true, Files::delete);
    }

    /** Copies bytes, verifies the expected digest, and retains only a weak owner reference. */
    public static synchronized String stage(ClassLoader payloadOwner, byte[] verifiedDll,
                                             String expectedSha256) throws IOException {
        if (configured == null) throw new IOException("Auth native custody is unavailable");
        return configured.stage(payloadOwner, verifiedDll, expectedSha256).toString();
    }

    @FunctionalInterface interface Deleter { void delete(Path file) throws IOException; }
    record Identity(String key, String created, String modified) { }
    record Receipt(String directory, Identity directoryIdentity, Identity fileIdentity,
                   long size, String sha256, long pid, long started) { }
    private record Live(WeakReference<ClassLoader> owner, FileChannel guard) { }

    /** Package visibility is solely for isolated ownership fixtures. */
    static final class Custodian implements AutoCloseable {
        final Path root;
        private final byte[] key;
        private final Map<Path, Live> live = new HashMap<>();
        private final Deleter deleter;
        private final ScheduledExecutorService worker;
        private boolean closed;

        Custodian(Path supplied, boolean daemon, Deleter deleter) throws IOException {
            root = supplied.toAbsolutePath().normalize();
            this.deleter = Objects.requireNonNull(deleter);
            requireCanonicalAncestors(root.getParent());
            try { Files.createDirectory(root); restrict(root, true); }
            catch (FileAlreadyExistsException existing) { assertPrivate(root, true); }
            Path keyFile = root.resolve(KEY);
            if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) {
                byte[] created = new byte[32]; new SecureRandom().nextBytes(created);
                try { Files.write(keyFile, created, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); restrict(keyFile, false); }
                catch (FileAlreadyExistsException race) { /* Other parent won creation. */ }
                finally { Arrays.fill(created, (byte) 0); }
            }
            assertPrivate(keyFile, false);
            key = readBounded(keyFile, 32);
            if (key.length != 32) throw new IOException("Invalid custody key");
            sweep();
            worker = daemon ? Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "celeste-auth-native-custody");
                thread.setDaemon(true);
                thread.setContextClassLoader(AuthNativeBridge.class.getClassLoader());
                return thread;
            }) : null;
            if (worker != null) worker.scheduleWithFixedDelay(this::quietSweep, 1, 1, TimeUnit.SECONDS);
        }

        synchronized Path stage(ClassLoader owner, byte[] input, String expected) throws IOException {
            if (closed || owner == null || owner == AuthNativeBridge.class.getClassLoader()
                || input == null || input.length == 0 || input.length > MAX_DLL
                || expected == null || !expected.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid auth native custody request");
            }
            if (live.size() >= MAX_DIRECTORIES) { sweep(); if (live.size() >= MAX_DIRECTORIES) throw new IOException("Too many retained native owners"); }
            byte[] snapshot = input.clone();
            try {
                if (!digest(snapshot).equals(expected)) throw new IOException("Native resource digest mismatch");
                assertPrivate(root, true);
                Path directory = Files.createTempDirectory(root, PREFIX).toAbsolutePath().normalize();
                restrict(directory, true);
                Path dll = directory.resolve(DLL);
                Identity directoryIdentity = identity(directory, true);
                boolean published = false;
                FileChannel guard = null;
                try {
                    Files.write(dll, snapshot, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    restrict(dll, false);
                    try (FileChannel channel = FileChannel.open(dll, StandardOpenOption.WRITE)) { channel.force(true); }
                    Receipt receipt = new Receipt(directory.toString(), directoryIdentity, identity(dll, false),
                        snapshot.length, expected, ProcessHandle.current().pid(), currentStart());
                    if (!System.getProperty("os.name", "").startsWith("Windows")) throw new IOException("Auth native custody requires Windows");
                    // JDK21 publicly exports these Windows sharing controls. Keep
                    // the exact verified file immutable through payload ownership.
                    guard = FileChannel.open(dll, StandardOpenOption.READ,
                        windowsSharingOption("NOSHARE_WRITE"), windowsSharingOption("NOSHARE_DELETE"));
                    verifyFile(directory, receipt);
                    writeReceipt(directory, receipt);
                    live.put(directory, new Live(new WeakReference<>(owner), guard));
                    published = true;
                    return dll;
                } finally {
                    if (!published) {
                        if (guard != null) guard.close();
                        // No recursive cleanup or adoption of a pre-existing path.
                        // Once a receipt exists normal custody handles retirement.
                        if (!Files.exists(directory.resolve(RECEIPT), LinkOption.NOFOLLOW_LINKS)
                            && directoryIdentity.equals(identity(directory, true))) {
                            if (Files.exists(dll, LinkOption.NOFOLLOW_LINKS)) {
                                assertPrivate(dll, false);
                                if (Files.size(dll) == snapshot.length && digest(readBounded(dll, MAX_DLL)).equals(expected)) Files.delete(dll);
                            }
                            try { Files.delete(directory); } catch (DirectoryNotEmptyException preserved) { }
                        }
                    }
                }
            } finally { Arrays.fill(snapshot, (byte) 0); }
        }

        private void quietSweep() { try { sweep(); } catch (IOException | RuntimeException ignored) { /* Preserve ambiguous ownership; retry boundedly. */ } }

        synchronized int sweep() throws IOException {
            if (closed) return 0;
            assertPrivate(root, true);
            int removed = 0;
            List<Path> candidates;
            try (var paths = Files.list(root)) {
                candidates = paths.filter(p -> p.getFileName().toString().startsWith(PREFIX)).limit(MAX_DIRECTORIES).toList();
            }
            for (Path directory : candidates) {
                try {
                    if (retire(directory)) { live.remove(directory); removed++; }
                } catch (IOException | RuntimeException ignored) { /* Malformed, locked, changed, or unknown entries remain untouched. */ }
            }
            live.keySet().removeIf(path -> !Files.exists(path, LinkOption.NOFOLLOW_LINKS));
            return removed;
        }

        private boolean retire(Path directory) throws IOException {
            assertPrivate(directory, true);
            if (!directory.getParent().equals(root)) throw new IOException("Invalid custody scope");
            Path receiptFile = directory.resolve(RECEIPT);
            assertPrivate(receiptFile, false);
            byte[] stored = readBounded(receiptFile, MAX_RECEIPT);
            Receipt receipt = decodeReceipt(stored);
            if (!receipt.directory.equals(directory.toString())
                || !receipt.directoryIdentity.equals(identity(directory, true))) throw new IOException("Custody directory changed");
            Live tracked = live.get(directory);
            if (tracked != null) {
                if (receipt.pid != ProcessHandle.current().pid() || receipt.started != currentStart()) throw new IOException("Custody process mismatch");
                if (tracked.owner.get() != null) return false;
                tracked.guard.close();
            } else if (processMayBeAlive(receipt.pid, receipt.started)) return false;
            try (var paths = Files.list(directory)) {
                if (paths.anyMatch(p -> !Set.of(DLL, RECEIPT).contains(p.getFileName().toString()))) throw new IOException("Unowned custody entries");
            }
            Path dll = directory.resolve(DLL);
            if (Files.exists(dll, LinkOption.NOFOLLOW_LINKS)) {
                verifyFile(directory, receipt);
                if (!Arrays.equals(stored, readBounded(receiptFile, MAX_RECEIPT))) throw new IOException("Custody receipt changed");
                // Windows may retain the DLL until native-library unloading completes.
                // Failed deletion is retried; no FreeLibrary or payload call is used.
                deleter.delete(dll);
            }
            if (!receipt.directoryIdentity.equals(identity(directory, true))
                || !Arrays.equals(stored, readBounded(receiptFile, MAX_RECEIPT))) throw new IOException("Custody ownership changed");
            Files.delete(receiptFile);
            Files.delete(directory);
            return true;
        }

        private void verifyFile(Path directory, Receipt receipt) throws IOException {
            assertPrivate(directory, true);
            if (!receipt.directoryIdentity.equals(identity(directory, true))) throw new IOException("Native directory changed");
            Path dll = directory.resolve(DLL);
            assertPrivate(dll, false);
            Identity before = identity(dll, false);
            if (!before.equals(receipt.fileIdentity) || Files.size(dll) != receipt.size
                || receipt.size < 1 || receipt.size > MAX_DLL || !receipt.sha256.matches("[0-9a-f]{64}")) throw new IOException("Native file ownership changed");
            if (!digest(readBounded(dll, MAX_DLL)).equals(receipt.sha256)
                || !before.equals(identity(dll, false)) || Files.size(dll) != receipt.size) throw new IOException("Native file content changed");
        }

        private void writeReceipt(Path directory, Receipt receipt) throws IOException {
            byte[] body;
            try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
                out.writeUTF("celeste-auth-native-custody-1"); out.writeUTF(receipt.directory);
                writeIdentity(out, receipt.directoryIdentity); writeIdentity(out, receipt.fileIdentity);
                out.writeLong(receipt.size); out.writeUTF(receipt.sha256); out.writeLong(receipt.pid); out.writeLong(receipt.started);
                out.flush(); body = bytes.toByteArray();
            }
            byte[] tag = mac(body);
            Path path = directory.resolve(RECEIPT);
            try (var stream = new DataOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                stream.writeInt(body.length); stream.write(body); stream.write(tag);
            }
            restrict(path, false);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        }

        private Receipt decodeReceipt(byte[] stored) throws IOException {
            try (var data = new DataInputStream(new ByteArrayInputStream(stored))) {
                int length = data.readInt();
                if (length < 1 || length > MAX_RECEIPT - 36 || stored.length != length + 36) throw new IOException("Invalid custody receipt");
                byte[] body = data.readNBytes(length), tag = data.readNBytes(32);
                if (!MessageDigest.isEqual(mac(body), tag)) throw new IOException("Unauthenticated custody receipt");
                try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
                    if (!in.readUTF().equals("celeste-auth-native-custody-1")) throw new IOException("Unknown custody receipt");
                    Receipt receipt = new Receipt(in.readUTF(), readIdentity(in), readIdentity(in), in.readLong(), in.readUTF(), in.readLong(), in.readLong());
                    if (in.available() != 0 || receipt.pid <= 0 || receipt.started <= 0) throw new IOException("Invalid custody lifetime");
                    return receipt;
                }
            }
        }

        private byte[] mac(byte[] body) throws IOException {
            try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(body); }
            catch (GeneralSecurityException failure) { throw new IOException("Custody authentication unavailable", failure); }
        }

        synchronized int tracked() { return live.size(); }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (worker != null) worker.shutdown();
            for (Live entry : live.values()) { try { entry.guard.close(); } catch (IOException ignored) { } }
            live.clear(); Arrays.fill(key, (byte) 0);
        }
    }

    private static long currentStart() throws IOException {
        return ProcessHandle.current().info().startInstant().orElseThrow(() -> new IOException("Process identity unavailable")).toEpochMilli();
    }
    private static OpenOption windowsSharingOption(String name) throws IOException {
        try {
            Object option = Class.forName("com.sun.nio.file.ExtendedOpenOption", true,
                ClassLoader.getPlatformClassLoader()).getField(name).get(null);
            if (option instanceof OpenOption value) return value;
            throw new IOException("Windows file sharing option unavailable");
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Windows native custody sharing controls unavailable", failure);
        }
    }
    private static boolean processMayBeAlive(long pid, long started) {
        var process = ProcessHandle.of(pid);
        return process.isPresent() && process.get().isAlive()
            && process.get().info().startInstant().map(time -> time.toEpochMilli() == started).orElse(true);
    }
    private static void writeIdentity(DataOutputStream out, Identity value) throws IOException { out.writeUTF(value.key); out.writeUTF(value.created); out.writeUTF(value.modified); }
    private static Identity readIdentity(DataInputStream in) throws IOException { return new Identity(in.readUTF(), in.readUTF(), in.readUTF()); }
    static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static byte[] readBounded(Path file, int maximum) throws IOException {
        if (Files.size(file) > maximum) throw new IOException("Custody file exceeds limit");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) throw new IOException("Custody file grew beyond limit");
            return bytes;
        }
    }
    private static Identity identity(Path path, boolean directory) throws IOException {
        BasicFileAttributes attributes = checked(path, directory);
        if (attributes.fileKey() == null && attributes.creationTime().toMillis() == 0) throw new IOException("File identity unavailable");
        // Windows JDK21 returns no basic fileKey. Timestamp/size/hash fallback
        // mirrors the existing support custodian; it is not native file-ID proof.
        return new Identity(String.valueOf(attributes.fileKey()), attributes.creationTime().toString(),
            directory ? "" : attributes.lastModifiedTime().toString());
    }
    private static BasicFileAttributes checked(Path path, boolean directory) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || (directory ? !attributes.isDirectory() : !attributes.isRegularFile())
            || !path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("Unsafe custody path");
        return attributes;
    }
    private static void requireCanonicalAncestors(Path parent) throws IOException {
        if (parent == null) throw new IOException("Custody root has no parent");
        for (Path path = parent.toAbsolutePath().normalize(); path != null; path = path.getParent()) checked(path, true);
    }
    private static UserPrincipal currentOwner(Path path) throws IOException {
        String user = System.getProperty("user.name");
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                // This canonical bridge is bootstrap-defined. NTSystem belongs
                // to jdk.security.auth and must resolve through the platform loader.
                Class<?> type = Class.forName("com.sun.security.auth.module.NTSystem", true, ClassLoader.getPlatformClassLoader());
                Object account = type.getConstructor().newInstance();
                user = (String) type.getMethod("getDomain").invoke(account) + "\\" + (String) type.getMethod("getName").invoke(account);
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IOException("Windows custody account identity unavailable", failure);
            }
        }
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(user);
    }
    private static void restrict(Path path, boolean directory) throws IOException {
        checked(path, directory);
        UserPrincipal owner = currentOwner(path);
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("Custody file has a foreign owner");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) posix.setPermissions(directory
            ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
            : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Private custody permissions unavailable");
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        }
        assertPrivate(path, directory);
    }
    private static void assertPrivate(Path path, boolean directory) throws IOException {
        checked(path, directory);
        UserPrincipal owner = currentOwner(path);
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException("Foreign custody owner");
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (posix.readAttributes().permissions().stream().anyMatch(p -> !p.name().startsWith("OWNER_"))) throw new IOException("Public custody permissions");
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null || acl.getAcl().stream().anyMatch(e -> e.type() == AclEntryType.ALLOW && !e.principal().equals(owner))) throw new IOException("Public custody permissions");
        }
    }
}
