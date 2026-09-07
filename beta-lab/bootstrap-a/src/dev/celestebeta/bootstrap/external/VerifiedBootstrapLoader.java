package dev.celestebeta.bootstrap.external;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipInputStream;
import org.objectweb.asm.ClassReader;

/** Memory storage for the single, signed internal bootstrap; never a generic URL loader. */
final class VerifiedBootstrapLoader extends ClassLoader implements AutoCloseable {
    static final String PREFIX = "dev.celestebeta.bootstrap.internal.";
    static final String LAYOUT = "META-INF/celeste-beta/runtime-layout.json";
    private static final String ENTRY = PREFIX.replace('.', '/') + "MemoryPayloadBootstrap.class";
    private static final String CAPABILITY = PREFIX.replace('.', '/') + "MemoryPayloadBootstrap$PreparedGame.class";
    private static final List<String> PARENT_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
        "com.oracle.", "org.w3c.", "org.xml.", "org.ietf.jgss.", "org.jcp.", "org.omg.", "netscape.javascript.",
        "net.minecraft.", "net.fabricmc.", "org.spongepowered.", "com.llamalad7.", "org.lwjgl.", "org.joml.",
        "org.slf4j.", "org.jetbrains.", "com.google.", "org.objectweb.asm.", "com.mojang.", "it.unimi.",
        "org.apache.", "io.netty.", "oshi.", "dev.redstones.mediaplayerinfo.", "dev.c4lam.celeste.");
    private final Map<String, byte[]> entries = new HashMap<>();
    // Names survive close so retired signed entries cannot silently fall back to a parent copy.
    private final Set<String> classNames = new HashSet<>();
    private final Set<String> resourceNames = new HashSet<>();
    private final String resourceId = java.util.UUID.randomUUID().toString();
    private boolean closed;

    VerifiedBootstrapLoader(byte[] archive, String expectedHash, ClassLoader parent) throws IOException {
        super("celeste-beta-internal-bootstrap", parent);
        if (archive.length == 0 || archive.length > 8 * 1024 * 1024 || !hash(archive).equals(expectedHash)) {
            throw new IOException("Internal bootstrap digest or size is invalid");
        }
        Map<String, DirectoryEntry> directory = directory(archive);
        boolean complete = false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            long expanded = 0;
            int count = 0;
            java.util.Set<String> names = new java.util.HashSet<>();
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                String name = entry.getName();
                if (++count > 512 || !name.matches("[A-Za-z0-9_$./-]+") || name.startsWith("/")
                    || name.contains("//") || Arrays.asList(name.split("/")).contains("..")
                    || Arrays.asList(name.split("/")).contains(".") || !names.add(name)) {
                    throw new IOException("Internal bootstrap archive path is invalid");
                }
                DirectoryEntry central = directory.remove(name);
                if (central == null) throw new IOException("Bootstrap member is absent from its ZIP directory");
                if (entry.isDirectory()) {
                    if (input.read() != -1) throw new IOException("Bootstrap directory contains bytes");
                    input.closeEntry(); central.verify(entry);
                    continue;
                }
                boolean classEntry = name.endsWith(".class");
                if (classEntry ? !allowedClass(name.substring(0, name.length() - 6).replace('/', '.')) : !allowedResource(name)) {
                    throw new IOException("Internal bootstrap contains unexpected content");
                }
                if (central.size <= 0 || central.size > 2 * 1024 * 1024 || expanded + central.size > 16 * 1024 * 1024) {
                    throw new IOException("Internal bootstrap advertised content exceeds bounds");
                }
                byte[] value = input.readNBytes(2 * 1024 * 1024 + 1);
                // Reject before closeEntry can drain a larger malformed stream.
                if (value.length != central.size) {
                    Arrays.fill(value, (byte) 0);
                    throw new IOException("Bootstrap expanded member differs from its bounded directory size");
                }
                try {
                    input.closeEntry();
                    central.verify(entry);
                } catch (IOException | RuntimeException failure) {
                    Arrays.fill(value, (byte) 0);
                    throw failure;
                }
                expanded += value.length;
                if (value.length == 0 || value.length > 2 * 1024 * 1024 || expanded > 16 * 1024 * 1024) {
                    Arrays.fill(value, (byte) 0);
                    throw new IOException("Internal bootstrap expanded content exceeds bounds");
                }
                entries.put(name, value);
                resourceNames.add(name);
                if (classEntry) {
                    validateClass(name, value);
                    classNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
                } else if (!name.equals("META-INF/MANIFEST.MF") && !name.equals(LAYOUT) && nativeMagic(value)) {
                    throw new IOException("Native content cannot be a bootstrap helper resource");
                }
            }
            if (!directory.isEmpty()) throw new IOException("Bootstrap ZIP directory contains missing members");
            byte[] manifest = entries.get("META-INF/MANIFEST.MF");
            if (manifest == null || !"B".equals(new Manifest(new ByteArrayInputStream(manifest))
                .getMainAttributes().getValue("Celeste-Beta-Bootstrap"))
                || !entries.containsKey(LAYOUT)
                || !entries.containsKey(ENTRY) || !entries.containsKey(CAPABILITY)) {
                throw new IOException("Internal bootstrap role, entrypoint or mapping is absent");
            }
            complete = true;
        } finally {
            if (!complete) close();
        }
    }

    @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!classNames.contains(name)) {
            if (name.startsWith(PREFIX)) throw new ClassNotFoundException(name);
            return super.loadClass(name, resolve);
        }
        if (closed) throw new ClassNotFoundException("Internal bootstrap is closed");
        Class<?> type = findLoadedClass(name);
        if (type == null) {
            byte[] bytes = entries.get(name.replace('.', '/') + ".class");
            if (bytes == null) throw new ClassNotFoundException(name);
            type = defineClass(name, bytes, 0, bytes.length);
        }
        if (resolve) resolveClass(type);
        return type;
    }

    @Override public synchronized InputStream getResourceAsStream(String name) {
        if (ownedResource(name)) {
            byte[] value = closed ? null : entries.get(name);
            return value == null ? null : new ByteArrayInputStream(value.clone());
        }
        return super.getResourceAsStream(name);
    }

    @Override public synchronized URL getResource(String name) {
        if (!ownedResource(name)) return super.getResource(name);
        if (closed || !entries.containsKey(name)) return null;
        String path = "/" + resourceId + "/" + name;
        try {
            return URL.of(java.net.URI.create("celeste-bootstrap:" + path), new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException {
                    if (!url.getPath().equals(path) || url.getQuery() != null || url.getRef() != null) {
                        throw new IOException("Bootstrap resource URL does not identify its retained entry");
                    }
                    return new URLConnection(url) {
                        @Override public void connect() { connected = true; }
                        @Override public InputStream getInputStream() throws IOException {
                            synchronized (VerifiedBootstrapLoader.this) {
                                byte[] value = closed ? null : entries.get(name);
                                if (value == null) throw new java.io.FileNotFoundException("Bootstrap resource is unavailable");
                                return new ByteArrayInputStream(value.clone());
                            }
                        }
                    };
                }
            });
        } catch (java.net.MalformedURLException impossible) { throw new AssertionError(impossible); }
    }

    @Override public synchronized java.util.Enumeration<URL> getResources(String name) throws IOException {
        if (!ownedResource(name)) return super.getResources(name);
        URL resource = getResource(name);
        return java.util.Collections.enumeration(resource == null ? List.of() : List.of(resource));
    }

    private boolean ownedResource(String name) {
        return resourceNames.contains(name) || name.equals(LAYOUT) || name.startsWith(PREFIX.replace('.', '/'));
    }

    private static boolean allowedClass(String name) {
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) return false;
        if (name.startsWith("dev.celestebeta.")) return name.startsWith(PREFIX);
        return PARENT_PACKAGES.stream().noneMatch(name::startsWith);
    }

    private static boolean allowedResource(String name) {
        if (name.equals("META-INF/MANIFEST.MF") || name.equals(LAYOUT)) return true;
        // The selected string protector emits opaque vaults here. No service,
        // Fabric, Mixin, native or other root metadata is admitted as helper data.
        if (!name.matches("META-INF/(?:[A-Za-z_$][A-Za-z0-9_$]*/)+[A-Za-z_$][A-Za-z0-9_$]*(?:-[A-Za-z_$][A-Za-z0-9_$]*)?\\.[A-Za-z_$][A-Za-z0-9_$]*")) return false;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*\\.(?:dll|so|dylib|jnilib|exe|lib|a|sf|rsa|dsa|ec)$")) return false;
        String binary = name.substring("META-INF/".length(), name.lastIndexOf('.')).replace('/', '.');
        int fragment = binary.indexOf('-'); if (fragment >= 0) binary = binary.substring(0, fragment);
        return !binary.startsWith("services.") && !binary.startsWith("versions.")
            && !binary.startsWith("native.") && !binary.startsWith("natives.")
            && !binary.startsWith("fabric.") && !binary.startsWith("mixin.") && !binary.startsWith("mixins.")
            && allowedClass(binary);
    }

    private static void validateClass(String name, byte[] bytes) throws IOException {
        try {
            if (bytes.length < 10 || java.nio.ByteBuffer.wrap(bytes).getInt() != 0xcafebabe) {
                throw new IOException("Bootstrap class path, identity or Java version is invalid");
            }
            ClassReader reader = new ClassReader(bytes);
            if (reader.readUnsignedShort(6) < 45 || reader.readUnsignedShort(6) > 65 || reader.readUnsignedShort(4) == 65535
                || !reader.getClassName().equals(name.substring(0, name.length() - 6))) {
                throw new IOException("Bootstrap class path, identity or Java version is invalid");
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Bootstrap class bytes are invalid", malformed);
        }
    }

    private static boolean nativeMagic(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z') return true;
        if (bytes.length < 4) return false;
        int magic = java.nio.ByteBuffer.wrap(bytes).getInt();
        return magic == 0x7f454c46 || magic == 0xfeedface || magic == 0xcefaedfe || magic == 0xfeedfacf
            || magic == 0xcffaedfe || magic == 0xcafebabe || magic == 0xbebafeca || magic == 0xcafebabf || magic == 0xbfbafeca;
    }

    @Override public synchronized void close() {
        closed = true;
        entries.values().forEach(bytes -> Arrays.fill(bytes, (byte) 0));
        entries.clear();
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private record DirectoryEntry(long size, long compressed, long crc, int method) {
        void verify(java.util.zip.ZipEntry entry) throws IOException {
            if (entry.getSize() != size || entry.getCompressedSize() != compressed || entry.getCrc() != crc || entry.getMethod() != method) {
                throw new IOException("Bootstrap ZIP directory disagrees with its member");
            }
        }
    }

    private static Map<String, DirectoryEntry> directory(byte[] archive) throws IOException {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(archive).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int end = -1;
        for (int offset = archive.length - 22; offset >= Math.max(0, archive.length - 65_557); offset--) {
            if (bytes.getInt(offset) == 0x06054b50 && offset + 22 + Short.toUnsignedInt(bytes.getShort(offset + 20)) == archive.length) {
                end = offset; break;
            }
        }
        if (end < 0 || bytes.getShort(end + 4) != 0 || bytes.getShort(end + 6) != 0
            || bytes.getShort(end + 8) != bytes.getShort(end + 10)) throw new IOException("Invalid ordinary bootstrap ZIP");
        int count = Short.toUnsignedInt(bytes.getShort(end + 10));
        long start = Integer.toUnsignedLong(bytes.getInt(end + 16)), length = Integer.toUnsignedLong(bytes.getInt(end + 12));
        if (count == 0 || count > 512 || start + length != end) throw new IOException("Invalid bootstrap ZIP directory bounds");
        int cursor = (int) start;
        Map<String, DirectoryEntry> entries = new HashMap<>();
        java.util.Set<Long> offsets = new java.util.HashSet<>();
        for (int i = 0; i < count; i++) {
            if (cursor + 46 > end || bytes.getInt(cursor) != 0x02014b50) throw new IOException("Invalid bootstrap ZIP directory member");
            int nameLength = Short.toUnsignedInt(bytes.getShort(cursor + 28));
            int extra = Short.toUnsignedInt(bytes.getShort(cursor + 30)), comment = Short.toUnsignedInt(bytes.getShort(cursor + 32));
            int next = cursor + 46 + nameLength + extra + comment;
            long local = Integer.toUnsignedLong(bytes.getInt(cursor + 42));
            if (next > end || nameLength == 0 || bytes.getShort(cursor + 34) != 0 || !offsets.add(local) || local >= start
                || local + 30 > start || bytes.getInt((int) local) != 0x04034b50
                || (Short.toUnsignedInt(bytes.getShort(cursor + 8)) & ~0x808) != 0) throw new IOException("Unsupported bootstrap ZIP directory member");
            String name = new String(archive, cursor + 46, nameLength, java.nio.charset.StandardCharsets.UTF_8);
            DirectoryEntry entry = new DirectoryEntry(Integer.toUnsignedLong(bytes.getInt(cursor + 24)),
                Integer.toUnsignedLong(bytes.getInt(cursor + 20)), Integer.toUnsignedLong(bytes.getInt(cursor + 16)),
                Short.toUnsignedInt(bytes.getShort(cursor + 10)));
            int localNameLength = Short.toUnsignedInt(bytes.getShort((int) local + 26));
            int localExtra = Short.toUnsignedInt(bytes.getShort((int) local + 28));
            long dataStart = local + 30 + localNameLength + localExtra;
            if (dataStart + entry.compressed > start || localNameLength != nameLength
                || bytes.getShort((int) local + 6) != bytes.getShort(cursor + 8)
                || Short.toUnsignedInt(bytes.getShort((int) local + 8)) != entry.method
                || !name.equals(new String(archive, (int) local + 30, localNameLength, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IOException("Bootstrap ZIP directory points to a different local member");
            }
            if ((bytes.getShort((int) local + 6) & 8) == 0
                && (Integer.toUnsignedLong(bytes.getInt((int) local + 14)) != entry.crc
                || Integer.toUnsignedLong(bytes.getInt((int) local + 18)) != entry.compressed
                || Integer.toUnsignedLong(bytes.getInt((int) local + 22)) != entry.size)) {
                throw new IOException("Bootstrap ZIP local sizes disagree with its directory");
            }
            if (entries.putIfAbsent(name, entry) != null) throw new IOException("Duplicate bootstrap ZIP directory member");
            cursor = next;
        }
        if (cursor != end) throw new IOException("Bootstrap ZIP directory has trailing data");
        return entries;
    }
}
