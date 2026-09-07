package dev.c4lam.celeste.beta.resources;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.util.Identifier;

/** Shared immutable asset snapshot; publication/lifecycle are serialized by its owner. */
final class MemoryResourceBytes {
    private static final int MAX_ENTRIES = 8192;
    private static final int MAX_ENTRY_BYTES = 32 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private volatile Contents contents;

    MemoryResourceBytes(byte[] metadata) {
        contents = new Contents(Map.of("pack.mcmeta", metadata), List.of(), 0);
    }

    MemoryResourceBytes(Map<String, byte[]> source, byte[] metadata) {
        Objects.requireNonNull(source, "verifiedAssets");
        if (source.isEmpty() || source.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Expected 1.." + MAX_ENTRIES + " memory resources");
        }
        Map<String, byte[]> copied = new TreeMap<>();
        Map<String, Identifier> identifiers = new TreeMap<>();
        long total = 0;
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            String name = entry.getKey();
            if (!safePath(name) || !(name.startsWith("assets/celeste/") || name.equals("pack.png"))) {
                throw new IllegalArgumentException("Unsupported or unsafe memory resource name");
            }
            byte[] bytes = Objects.requireNonNull(entry.getValue(), "resource bytes");
            total += bytes.length;
            if (bytes.length > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Memory resource size limit exceeded");
            }
            copied.put(name, bytes.clone());
            if (name.startsWith("assets/celeste/")) {
                identifiers.put(name, Identifier.of("celeste", name.substring("assets/celeste/".length())));
            }
        }
        if (identifiers.isEmpty()) {
            throw new IllegalArgumentException("Memory resources need at least one Celeste asset");
        }
        copied.put("pack.mcmeta", metadata);
        contents = new Contents(Collections.unmodifiableMap(copied), List.copyOf(identifiers.values()), total);
    }

    void install(Map<String, byte[]> verifiedAssets) {
        Contents previous = contents;
        if (previous == null || !previous.identifiers().isEmpty()) {
            throw new IllegalStateException("Asset store is closed or already populated");
        }
        // Validation/copy happen before publication; rejected input is retryable.
        MemoryResourceBytes validated = new MemoryResourceBytes(verifiedAssets, previous.bytes().get("pack.mcmeta"));
        contents = validated.contents;
    }

    byte[] bytes(String name) {
        Contents snapshot = contents;
        return snapshot == null ? null : snapshot.bytes().get(name);
    }

    List<Identifier> identifiers() {
        Contents snapshot = contents;
        return snapshot == null ? List.of() : snapshot.identifiers();
    }

    long retainedAssetBytes() {
        Contents snapshot = contents;
        return snapshot == null ? 0 : snapshot.assetBytes();
    }

    void close() {
        contents = null;
    }

    static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.')) {
                    return false;
                }
            }
        }
        return true;
    }

    private record Contents(Map<String, byte[]> bytes, List<Identifier> identifiers, long assetBytes) { }
}
