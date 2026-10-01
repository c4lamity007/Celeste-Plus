package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Immutable classified terrain; no Minecraft objects cross the worker boundary. */
public final class SuspiciousSnapshots {
    private SuspiciousSnapshots() { }
    public static long key(int x, int z) { return (long) x << 32 | z & 0xffffffffL; }
    public static int x(long key) { return (int) (key >> 32); }
    public static int z(long key) { return (int) key; }

    public static final class Chunk {
        public final int x, z, floor, top, base;
        public final long observedMillis, revision;
        private final short[][] sections;
        private final int[] surface;
        private final long bytes;
        private final boolean hasUnknown;

        /** Ownership of arrays transfers to this object; the caller must not mutate them. */
        public Chunk(int x, int z, int floor, int top, short[][] sections, int[] surface,
                     long observedMillis, long revision) {
            this.x = x; this.z = z; this.floor = floor; this.top = top;
            this.base = Math.floorDiv(floor, 16) * 16;
            this.sections = sections; this.surface = surface;
            this.observedMillis = observedMillis; this.revision = revision;
            long size = 1200; boolean unknown = false;
            for (int s = 0; s < sections.length; s++) {
                short[] section = sections[s];
                if (section == null) { unknown = true; continue; }
                size += 32L + section.length * 2L;
                if (section.length == 1) unknown |= section[0] == SuspiciousAnalysis.UNKNOWN;
                else for (int i = 0; i < section.length; i++) {
                    int y = base + s * 16 + (i >> 8);
                    if (y >= floor && y < top && section[i] == SuspiciousAnalysis.UNKNOWN) { unknown = true; break; }
                }
            }
            this.bytes = size;
            this.hasUnknown = unknown;
        }

        public int cell(int x, int y, int z) {
            if (y < floor || y >= top || x >> 4 != this.x || z >> 4 != this.z) return SuspiciousAnalysis.UNKNOWN;
            short[] section = sections[(y - base) >> 4];
            if (section == null) return SuspiciousAnalysis.UNKNOWN;
            return section[section.length == 1 ? 0 : ((y & 15) << 8 | (z & 15) << 4 | x & 15)] & 0xffff;
        }

        public int surface(int x, int z) { return surface[(z & 15) << 4 | x & 15]; }
        public long bytes() { return bytes; }
        private Chunk fillUnknown(Chunk newer) {
            if (!hasUnknown) return this;
            short[][] merged = null;
            for (int s = 0; s < sections.length; s++) {
                short[] old = sections[s], fresh = newer.sections[s];
                if (fresh == null) continue;
                if (old == null || old.length == 1 && old[0] == SuspiciousAnalysis.UNKNOWN) {
                    if (merged == null) merged = sections.clone();
                    merged[s] = fresh;
                } else if (old.length > 1) {
                    short[] fixed = null;
                    for (int i = 0; i < old.length; i++) if (old[i] == SuspiciousAnalysis.UNKNOWN) {
                        short supplied = fresh[fresh.length == 1 ? 0 : i];
                        if (supplied == SuspiciousAnalysis.UNKNOWN) continue;
                        if (fixed == null) fixed = old.clone();
                        fixed[i] = supplied;
                    }
                    if (fixed != null) {
                        if (merged == null) merged = sections.clone();
                        merged[s] = fixed;
                    }
                }
            }
            int[] heights = surface;
            for (int i = 0; i < surface.length; i++) if (surface[i] == Integer.MIN_VALUE && newer.surface[i] != Integer.MIN_VALUE) {
                if (heights == surface) heights = surface.clone();
                heights[i] = newer.surface[i];
            }
            return merged == null && heights == surface ? this : new Chunk(x, z, floor, top,
                merged == null ? sections : merged, heights, observedMillis, newer.revision);
        }
        public int representativeSurface() {
            int[] values = java.util.Arrays.stream(surface).filter(y -> y != Integer.MIN_VALUE).toArray();
            if (values.length == 0) return Integer.MIN_VALUE;
            java.util.Arrays.sort(values);
            return values[values.length * 3 / 4]; // Upper quartile keeps a flat marker above most local terrain.
        }
    }

    public record View(Map<Long, Chunk> chunks, int floorY, int topY) implements SuspiciousAnalysis.Terrain {
        public View { chunks = Map.copyOf(chunks); }
        @Override public int cell(int x, int y, int z) {
            if (y < floorY || y >= topY) return SuspiciousAnalysis.UNKNOWN;
            Chunk chunk = chunks.get(key(x >> 4, z >> 4));
            return chunk == null ? SuspiciousAnalysis.UNKNOWN : chunk.cell(x, y, z);
        }
        @Override public int surfaceY(int x, int z) {
            Chunk chunk = chunks.get(key(x >> 4, z >> 4));
            return chunk == null ? Integer.MIN_VALUE : chunk.surface(x, z);
        }
    }

    /** First observations never get replaced by later growth, even after memory eviction. */
    public static final class Cache {
        public static final int MAX_CHUNKS = 512, MAX_SEEN = 16384;
        public static final long MAX_BYTES = 64L * 1024 * 1024;
        private final LinkedHashMap<Long, Chunk> live = new LinkedHashMap<>();
        private final LinkedHashMap<Long, Chunk> first = new LinkedHashMap<>();
        private final Set<Long> seen = new HashSet<>();
        public Chunk live(long key) { return live.get(key); }
        public Map<Long, Chunk> live() { return Map.copyOf(live); }
        public Map<Long, Chunk> first() { return Map.copyOf(first); }
        public int size() { return live.size(); }
        public boolean historyFull() { return seen.size() >= MAX_SEEN; }
        public void put(Chunk chunk) {
            long key = key(chunk.x, chunk.z);
            live.remove(key); live.put(key, chunk);
            if (!seen.contains(key) && seen.size() < MAX_SEEN) {
                seen.add(key); first.put(key, chunk);
            } else if (first.containsKey(key)) first.put(key, first.get(key).fillUnknown(chunk));
            trim();
        }
        public void unload(long key) { live.remove(key); }
        public void clearLive() { live.clear(); }
        public void clear() { live.clear(); first.clear(); seen.clear(); }
        public long bytes() {
            long size = 0;
            for (Chunk c : live.values()) size += c.bytes();
            for (var e : first.entrySet()) if (live.get(e.getKey()) != e.getValue()) size += e.getValue().bytes();
            return size;
        }
        private void trim() {
            while (live.size() > MAX_CHUNKS || first.size() > MAX_CHUNKS || bytes() > MAX_BYTES) {
                // Keep current terrain ahead of old, unloaded history under memory pressure.
                Long old = null;
                for (Long candidate : first.keySet()) if (!live.containsKey(candidate)) { old = candidate; break; }
                if (old != null) { first.remove(old); continue; }
                if (!live.isEmpty()) {
                    old = live.keySet().iterator().next(); live.remove(old); first.remove(old);
                } else if (!first.isEmpty()) first.remove(first.keySet().iterator().next());
                else break;
            }
        }
    }
}
