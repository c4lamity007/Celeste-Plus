package dev.c4lam.celeste.module.delivery;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Verified inert assets only. No module class, loader, callback, or executable entry is retained. */
public final class ModuleResourceAssets {
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    private static final long MAX_MODULE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_LIVE_BYTES = 256L * 1024 * 1024;
    private static final Map<Object, Registration> OWNERS = new IdentityHashMap<>();
    private static final Map<String, List<Registration>> PATHS = new TreeMap<>();
    private static final Map<Long, Registration> TOKENS = new HashMap<>();
    private static long nextToken;
    private static long retainedBytes;
    private static Consumer<Set<String>> installationObserver;
    private static Consumer<Set<String>> retirementObserver;
    private ModuleResourceAssets() { }

    /** Takes ownership of array values only on success. Caller must never mutate them afterward. */
    public static void install(ModuleScope scope, Map<String, byte[]> assets) {
        Objects.requireNonNull(scope, "scope");
        synchronized (scope) {
            if (!scope.open()) throw new IllegalStateException("Module scope is retiring");
            synchronized (ModuleResourceAssets.class) {
                Registration registration = prepare(scope, scope, assets, true);
                // Installed before factories; reverse disposal releases GPU caches before these bytes.
                scope.releaseAfterCallbacks(registration);
                publishAndNotify(registration);
            }
        }
    }

    /** Fixed internal-pack bytes remain owned by their existing host store. */
    public static synchronized AutoCloseable registerResident(Object owner, Map<String, byte[]> assets) {
        Objects.requireNonNull(owner, "owner");
        Registration registration = prepare(owner, null, assets, false);
        publishAndNotify(registration);
        return registration;
    }

    private static Registration prepare(Object owner, ModuleScope scope, Map<String, byte[]> source, boolean wipe) {
        Objects.requireNonNull(source, "assets");
        if (OWNERS.containsKey(owner)) throw new IllegalStateException("Assets already installed for owner");
        if (source.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many module assets");
        Map<String, byte[]> copied = new TreeMap<>();
        long size = 0;
        for (var entry : source.entrySet()) {
            String path = entry.getKey();
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "asset bytes");
            if (!safePath(path) || bytes.length > (wipe ? MAX_ENTRY_BYTES : 32 * 1024 * 1024))
                throw new IllegalArgumentException("Unsupported module asset");
            size += bytes.length;
            if (size > (wipe ? MAX_MODULE_BYTES : MAX_LIVE_BYTES) || retainedBytes + size > MAX_LIVE_BYTES)
                throw new IllegalArgumentException("Module asset memory limit exceeded");
            List<Registration> existing = PATHS.get(path);
            if (existing != null) for (Registration registration : existing) {
                if (!Arrays.equals(bytes, registration.assets.get(path)))
                    throw new IllegalArgumentException("Conflicting verified asset: " + path);
            }
            copied.put(path, bytes);
        }
        return new Registration(owner, scope, Map.copyOf(copied), size, wipe);
    }

    public static synchronized void setInstallationObserver(Consumer<Set<String>> observer) {
        requireResident(observer);
        if (installationObserver != null && installationObserver != observer) throw new IllegalStateException("Asset installation observer already set");
        installationObserver = observer;
    }
    public static synchronized void setRetirementObserver(Consumer<Set<String>> observer) {
        requireResident(observer);
        if (retirementObserver != null && retirementObserver != observer) throw new IllegalStateException("Asset retirement observer already set");
        retirementObserver = observer;
    }
    private static void requireResident(Object observer) {
        Objects.requireNonNull(observer);
        if (observer.getClass().getClassLoader() != ModuleResourceAssets.class.getClassLoader())
            throw new IllegalArgumentException("Asset cache observers must be resident");
    }
    private static void publishAndNotify(Registration registration) {
        publish(registration);
        try {
            if (installationObserver != null) installationObserver.accept(registration.assets.keySet());
        } catch (RuntimeException | Error failure) {
            unpublish(registration, false);
            throw failure;
        }
    }
    private static void publish(Registration registration) {
        OWNERS.put(registration.owner, registration);
        TOKENS.put(registration.token, registration);
        retainedBytes += registration.size;
        for (String path : registration.assets.keySet())
            PATHS.computeIfAbsent(path, ignored -> new ArrayList<>()).add(registration);
    }

    /** Defensive bytes for compilers; a scoped caller cannot read another module's private assets. */
    public static synchronized byte[] bytes(ModuleScope scope, String path) {
        byte[] bytes = lookup(scope, path);
        return bytes == null ? null : bytes.clone();
    }
    public static byte[] bytes(String path) { return bytes(ModuleScope.current(), path); }

    public static synchronized InputStream open(String path) {
        Registration registration = find(ModuleScope.current(), path);
        return registration == null ? null : new RevocableInputStream(registration.token, path);
    }
    public static synchronized boolean contains(String path) {
        return lookup(ModuleScope.current(), path) != null;
    }
    public static synchronized List<String> paths() {
        return PATHS.keySet().stream().filter(path -> lookup(ModuleScope.current(), path) != null).toList();
    }
    public static synchronized long retainedBytes() { return retainedBytes; }
    public static synchronized boolean hasModuleAssets(ModuleScope scope) { return scope != null && OWNERS.containsKey(scope); }

    private static byte[] lookup(ModuleScope scope, String path) {
        Registration entry = find(scope, path);
        return entry == null ? null : entry.assets.get(path);
    }
    private static Registration find(ModuleScope scope, String path) {
        if (!safePath(path)) return null;
        List<Registration> entries = PATHS.get(path);
        if (entries == null) return null;
        if (scope != null) {
            if (!scope.open()) return null;
            Registration own = OWNERS.get(scope);
            if (own != null && own.assets.containsKey(path)) return own;
            for (Registration entry : entries) if (entry.scope == null) return entry;
            return null;
        }
        for (Registration entry : entries)
            if (entry.scope == null || entry.scope.open()) return entry;
        return null;
    }

    private static boolean safePath(String path) {
        if (path == null || !path.startsWith("assets/celeste/") || path.length() > 512) return false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')) return false;
            }
        }
        return true;
    }

    private static final class Registration implements AutoCloseable {
        private final long token = ++nextToken;
        private final Object owner;
        private final ModuleScope scope;
        private final Map<String, byte[]> assets;
        private final long size;
        private final boolean wipe;
        private Registration(Object owner, ModuleScope scope, Map<String, byte[]> assets, long size, boolean wipe) {
            this.owner = owner; this.scope = scope; this.assets = assets; this.size = size; this.wipe = wipe;
        }
        @Override public void close() {
            synchronized (ModuleResourceAssets.class) {
                Set<String> removed = unpublish(this, wipe);
                if (!removed.isEmpty() && retirementObserver != null) retirementObserver.accept(removed);
            }
        }
    }
    private static Set<String> unpublish(Registration registration, boolean wipe) {
        if (OWNERS.get(registration.owner) != registration) return Set.of();
        OWNERS.remove(registration.owner);
        TOKENS.remove(registration.token);
        retainedBytes -= registration.size;
        Set<String> removed = new HashSet<>();
        for (String path : registration.assets.keySet()) {
            List<Registration> entries = PATHS.get(path);
            entries.remove(registration);
            if (entries.isEmpty()) { PATHS.remove(path); removed.add(path); }
        }
        if (wipe) for (byte[] bytes : registration.assets.values()) Arrays.fill(bytes, (byte) 0);
        return Set.copyOf(removed);
    }

    /** A retained stream holds only an opaque generation and path, never assets or a module scope. */
    private static final class RevocableInputStream extends InputStream {
        private final long token;
        private final String path;
        private int position;
        private boolean closed;
        private RevocableInputStream(long token, String path) { this.token = token; this.path = path; }
        private byte[] live() throws IOException {
            Registration owner = TOKENS.get(token);
            if (closed || owner == null) throw new IOException("Module resource was retired");
            return owner.assets.get(path);
        }
        @Override public synchronized int read() throws IOException {
            synchronized (ModuleResourceAssets.class) {
                byte[] bytes = live();
                return position >= bytes.length ? -1 : bytes[position++] & 255;
            }
        }
        @Override public synchronized int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            synchronized (ModuleResourceAssets.class) {
                byte[] bytes = live();
                if (length == 0) return 0;
                if (position >= bytes.length) return -1;
                int count = Math.min(length, bytes.length - position);
                System.arraycopy(bytes, position, target, offset, count);
                position += count;
                return count;
            }
        }
        @Override public synchronized int available() throws IOException {
            synchronized (ModuleResourceAssets.class) { return live().length - position; }
        }
        @Override public synchronized void close() { closed = true; }
    }
}
