package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Immutable-snapshot analysis. No Minecraft objects or world access belong in this class. */
public final class SuspiciousAnalysis {
    private SuspiciousAnalysis() { }

    public static final int UNKNOWN = 0, AIR = 1, WATER = 2, STONE = 3, DEEPSLATE = 4,
        HORIZONTAL_DEEPSLATE = 5, DIRT = 6, COARSE_DIRT = 7, ROOTED_DIRT = 8,
        PODZOL = 9, MYCELIUM = 10, VINE = 11, KELP = 12, KELP_PLANT = 13,
        BUDDING = 14, AMETHYST = 15, CALCITE = 16, BASALT = 17, SMOOTH_BASALT = 18,
        SMALL_BUD = 19, MEDIUM_BUD = 20, LARGE_BUD = 21, CLUSTER = 22,
        COBBLE = 23, COBBLED_DEEPSLATE = 24, STRUCTURE = 25, OTHER = 26,
        REPLACEABLE = 27, WATERLOGGED_OTHER = 28, LAVA = 29, FLOWING_WATER = 30;

    // Short-sized encoding: kind 0..7, age 8..12, facing 13..15.
    // Facing follows vanilla Direction ids: down, up, north, south, west, east.
    public static int cell(int kind, int age, int facing) {
        return (kind & 255) | ((age & 31) << 8) | ((facing & 7) << 13);
    }
    public static int kind(int cell) { return cell & 255; }
    public static int age(int cell) { return (cell >>> 8) & 31; }
    public static int facing(int cell) { return (cell >>> 13) & 7; }

    public interface Terrain {
        int cell(int x, int y, int z);
        int floorY();
        int topY(); // Exclusive.
        int surfaceY(int x, int z); // Top solid/water block, or Integer.MIN_VALUE if unknown.
    }

    public enum Check {
        ROTATED_DEEPSLATE(30), MISMATCHED_SOIL(10), GROUNDED_VINES(10),
        MATURE_GEODE(15), SPARSE_GEODE_CRYSTALS(15), MISSING_BUDDING_AMETHYST(25),
        SURFACE_KELP(15), MATURE_KELP_AGE(15), BURIED_ACCESS_PATH(35), OPEN_SHAFTS(-20);
        public final int weight;
        Check(int weight) { this.weight = weight; }
        public int weight() { return weight; }
    }
    public record Pos(int x, int y, int z) {
        public Pos offset(int dx, int dy, int dz) { return new Pos(x + dx, y + dy, z + dz); }
    }
    public record ChunkSummary(int chunkX, int chunkZ, List<Pos> candidates, boolean complete) {
        public ChunkSummary {
            // Our packed backing is immutable. Copying it would retain one object per cell again.
            if (!(candidates instanceof PackedPositions)) candidates = List.copyOf(candidates);
        }
        public long candidateBytes() {
            return candidates instanceof PackedPositions ? 48L + candidates.size() * 4L
                : 48L + candidates.size() * 32L;
        }
    }

    /** Four bytes per cached position; Pos values are decoded only for the current worker analysis. */
    private static final class PackedPositions extends AbstractList<Pos> implements RandomAccess {
        private final int baseX, baseZ;
        private final int[] positions;
        private PackedPositions(int chunkX, int chunkZ, int[] source, int size) {
            baseX = chunkX << 4; baseZ = chunkZ << 4;
            positions = Arrays.copyOf(source, size);
        }
        @Override public int size() { return positions.length; }
        @Override public Pos get(int index) {
            int packed = positions[Objects.checkIndex(index, positions.length)];
            return new Pos(baseX + (packed & 15), packed >> 8, baseZ + ((packed >>> 4) & 15));
        }
    }
    public record Finding(Check check, long featureId, int chunkX, int chunkZ) { }
    public record Evaluation(int score, Map<Check, Integer> contributions, List<Finding> findings) {
        public Evaluation {
            contributions = Map.copyOf(contributions);
            findings = List.copyOf(findings);
        }
        public boolean qualifies(int threshold) { return score >= threshold; }
    }

    public static final int MAX_CANDIDATES_PER_CHUNK = 8192;
    public static final int MAX_GEODE_CELLS = 4096;
    public static final int MAX_GEODES_PER_WINDOW = 16;
    public static final int MIN_VINE_ENDINGS = 8;
    public static final double GROUNDED_VINE_RATIO = 0.75;
    public static final int MIN_KELP_TIPS = 12;
    public static final double SURFACE_KELP_RATIO = 0.80;
    public static final double MATURE_KELP_RATIO = 0.75;
    public static final int MIN_GEODE_INNER = 32;
    public static final int MIN_GEODE_CALCITE = 12;
    public static final int MIN_GEODE_BASALT = 8;
    public static final int MIN_GEODE_CRYSTALS = 12;
    public static final double MATURE_GEODE_RATIO = 0.80;
    public static final int MIN_GROWTH_FACES = 20;
    public static final double SPARSE_CRYSTAL_RATIO = 0.15;
    public static final int MIN_STRIPPED_GEODE_INNER = 64;
    public static final double MISSING_BUDDING_RATIO = 0.02;
    private static final int[][] DIRECTIONS = {{0,-1,0},{0,1,0},{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};
    private static final int[][] HORIZONTAL = {{0,0,-1},{0,0,1},{-1,0,0},{1,0,0}};

    /** Run once for a changed immutable chunk snapshot; overlapping windows reuse the result. */
    public static ChunkSummary summarize(Terrain terrain, int chunkX, int chunkZ) {
        if (terrain.floorY() < -8388608 || terrain.topY() > 8388608)
            throw new IllegalArgumentException("Scan height cannot be represented by packed positions");
        int[] candidates = new int[128];
        int count = 0;
        boolean complete = true;
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int y = terrain.floorY(); y < terrain.topY(); y++) {
            interrupted();
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    int k = kind(terrain.cell(x, y, z));
                    if (k == UNKNOWN) complete = false;
                    if (candidate(k) && (k != DIRT || possibleDirt(terrain, x, y, z))) {
                        if (count < MAX_CANDIDATES_PER_CHUNK) {
                            if (count == candidates.length) candidates = Arrays.copyOf(candidates,
                                Math.min(MAX_CANDIDATES_PER_CHUNK, candidates.length * 2));
                            candidates[count++] = y << 8 | (z & 15) << 4 | x & 15;
                        } else complete = false;
                    }
                }
            }
        }
        return new ChunkSummary(chunkX, chunkZ, new PackedPositions(chunkX, chunkZ, candidates, count), complete);
    }

    private static boolean possibleDirt(Terrain terrain, int x, int y, int z) {
        // Neighbor chunks can change independently of this immutable chunk's summary cache key.
        // Keep border dirt so a later cross-border repair or soil change can still seed detection.
        if ((x & 15) == 0 || (x & 15) == 15 || (z & 15) == 0 || (z & 15) == 15) return true;
        int rock = 0;
        for (int[] d : DIRECTIONS) {
            int k = kind(terrain.cell(x + d[0], y + d[1], z + d[2]));
            // Summary caching must not lose boundary candidates before adjacent chunks arrive.
            if (k == UNKNOWN || soilType(k)) return true;
            if (naturalRock(k) && ++rock >= 2) return true;
        }
        return false;
    }

    private static boolean candidate(int k) {
        return k == HORIZONTAL_DEEPSLATE || k == DIRT || k == VINE || k == KELP
            || k == AMETHYST || k == BUDDING || k == COBBLE || k == COBBLED_DEEPSLATE;
    }

    public static Evaluation evaluate(Terrain terrain, int centerX, int centerZ,
            Collection<ChunkSummary> summaries, Set<Check> enabled, Collection<Finding> geometry) {
        return evaluate(terrain, terrain, centerX, centerZ, summaries, summaries, enabled, geometry);
    }

    /** Growth checks read only the first-observation terrain; replacement checks use current terrain. */
    public static Evaluation evaluate(Terrain current, Terrain firstGrowth, int centerX, int centerZ,
            Collection<ChunkSummary> currentSummaries, Collection<ChunkSummary> firstSummaries,
            Set<Check> enabled, Collection<Finding> geometry) {
        Window live = new Window(current, centerX, centerZ);
        Window first = new Window(firstGrowth, centerX, centerZ);
        List<Pos> liveSeeds = seeds(currentSummaries, centerX, centerZ);
        List<Pos> firstSeeds = seeds(firstSummaries, centerX, centerZ);
        List<Finding> found = new ArrayList<>();
        if (enabled.contains(Check.ROTATED_DEEPSLATE)) rotated(live, liveSeeds, found);
        if (enabled.contains(Check.MISMATCHED_SOIL)) soil(live, liveSeeds, found);
        if (enabled.contains(Check.GROUNDED_VINES)) vines(first, firstSeeds, found);
        if (enabled.contains(Check.SURFACE_KELP) || enabled.contains(Check.MATURE_KELP_AGE)) {
            kelp(first, firstSeeds, found);
        }
        if (enabled.contains(Check.MATURE_GEODE) || enabled.contains(Check.SPARSE_GEODE_CRYSTALS)
                || enabled.contains(Check.MISSING_BUDDING_AMETHYST)) geodes(first, firstSeeds, found);
        for (Finding f : geometry) {
            if (Math.abs((long) f.chunkX() - centerX) <= 1 && Math.abs((long) f.chunkZ() - centerZ) <= 1
                    && (f.check() == Check.BURIED_ACCESS_PATH || f.check() == Check.OPEN_SHAFTS)) found.add(f);
        }
        return score(found, enabled);
    }

    /** Fixed per-check contributions, correlated-family caps, then one optional deduction. */
    public static Evaluation score(Collection<Finding> findings, Set<Check> enabled) {
        LinkedHashSet<Finding> unique = new LinkedHashSet<>();
        EnumSet<Check> present = EnumSet.noneOf(Check.class);
        for (Finding f : findings) if (enabled.contains(f.check())) {
            unique.add(f);
            present.add(f.check());
        }
        EnumMap<Check, Integer> contributions = new EnumMap<>(Check.class);
        for (Check check : present) {
            if (check != Check.OPEN_SHAFTS && !geodeCheck(check) && !kelpCheck(check)) {
                contributions.put(check, check.weight);
            }
        }
        capped(contributions, present, 25, Check.MISSING_BUDDING_AMETHYST,
            Check.MATURE_GEODE, Check.SPARSE_GEODE_CRYSTALS);
        capped(contributions, present, 20, Check.SURFACE_KELP, Check.MATURE_KELP_AGE);
        if (present.contains(Check.OPEN_SHAFTS)) contributions.put(Check.OPEN_SHAFTS, -20);
        int score = Math.max(0, contributions.values().stream().mapToInt(Integer::intValue).sum());
        return new Evaluation(score, contributions, new ArrayList<>(unique));
    }

    private static void capped(Map<Check, Integer> out, Set<Check> present, int cap, Check... order) {
        for (Check check : order) if (present.contains(check)) {
            int contribution = Math.min(cap, check.weight);
            if (contribution > 0) out.put(check, contribution);
            cap -= contribution;
        }
    }
    private static boolean geodeCheck(Check c) {
        return c == Check.MATURE_GEODE || c == Check.SPARSE_GEODE_CRYSTALS || c == Check.MISSING_BUDDING_AMETHYST;
    }
    private static boolean kelpCheck(Check c) { return c == Check.SURFACE_KELP || c == Check.MATURE_KELP_AGE; }

    private static List<Pos> seeds(Collection<ChunkSummary> summaries, int cx, int cz) {
        Map<Long, ChunkSummary> chunks = new HashMap<>();
        for (ChunkSummary s : summaries) if (Math.abs((long) s.chunkX() - cx) <= 1
                && Math.abs((long) s.chunkZ() - cz) <= 1) chunks.put(chunkId(s.chunkX(), s.chunkZ()), s);
        List<Pos> result = new ArrayList<>();
        for (int z = cz - 1; z <= cz + 1; z++) for (int x = cx - 1; x <= cx + 1; x++) {
            ChunkSummary s = chunks.get(chunkId(x, z));
            if (s != null) result.addAll(s.candidates());
        }
        return result;
    }

    private static void rotated(Window t, List<Pos> seeds, List<Finding> out) {
        for (Pos p : seeds) if (t.kind(p) == HORIZONTAL_DEEPSLATE) {
            int rock = 0, deep = 0;
            boolean known = true, structure = false;
            for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
                if (x == 0 && y == 0 && z == 0) continue;
                int k = t.kind(p.offset(x, y, z));
                if (k == STRUCTURE) structure = true;
                if (Math.abs(x) <= 1 && Math.abs(y) <= 1 && Math.abs(z) <= 1) {
                    if (k == UNKNOWN) known = false;
                    if (naturalRock(k)) rock++;
                    if (k == DEEPSLATE) deep++;
                }
            }
            if (known && !structure && rock >= 20 && deep >= 12) {
                out.add(finding(Check.ROTATED_DEEPSLATE, p));
                return;
            }
        }
    }

    private static void soil(Window t, List<Pos> seeds, List<Finding> out) {
        Set<Pos> visited = new HashSet<>();
        for (Pos seed : seeds) {
            if (t.kind(seed) != DIRT || visited.contains(seed)) continue;
            boolean soilNear = false;
            for (int[] d : HORIZONTAL) if (soilType(t.kind(offset(seed, d)))) soilNear = true;
            if (!soilNear) continue;
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            List<Pos> patch = new ArrayList<>();
            queue.add(seed); visited.add(seed);
            boolean overflow = false, known = true, structure = false;
            int minX = seed.x(), maxX = minX, minY = seed.y(), maxY = minY, minZ = seed.z(), maxZ = minZ;
            while (!queue.isEmpty() && patch.size() <= 12) {
                Pos p = queue.removeFirst(); patch.add(p);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
                for (int[] d : DIRECTIONS) {
                    Pos q = offset(p, d); int k = t.kind(q);
                    if (k == UNKNOWN) known = false;
                    if (k == STRUCTURE) structure = true;
                    if (k == DIRT && visited.add(q)) queue.addLast(q);
                }
            }
            if (!queue.isEmpty() || patch.size() > 12) overflow = true;
            if (overflow || !known || structure || patch.size() < 2 || maxX - minX > 3 || maxY - minY > 2 || maxZ - minZ > 3) continue;
            // A compact regular patch must sit in a consistently different soil, not a mixed deposit.
            Set<Pos> cells = new HashSet<>(patch), boundary = new HashSet<>();
            Map<Integer, Integer> soilCounts = new HashMap<>();
            int suitable = 0, contacts = 0, surface = 0;
            for (Pos p : patch) {
                int surfaceY = t.surfaceY(p.x(), p.z());
                if (surfaceY != Integer.MIN_VALUE && p.y() >= surfaceY - 2) surface++;
                for (int[] d : HORIZONTAL) {
                    Pos q = offset(p, d);
                    if (cells.contains(q) || !boundary.add(q)) continue;
                    int k = t.kind(q);
                    if (k == UNKNOWN) known = false;
                    if (k != AIR && k != REPLACEABLE) contacts++;
                    if (soilType(k)) { suitable++; soilCounts.merge(k, 1, Integer::sum); }
                }
            }
            int dominant = soilCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (known && surface > 0 && suitable >= 8 && contacts > 0 && suitable >= contacts * 0.8
                    && dominant >= suitable * 0.8 && patch.size() >= volume * 0.75) {
                out.add(finding(Check.MISMATCHED_SOIL, seed));
                return;
            }
        }
    }

    private record Sample(Pos pos, boolean first, boolean second) { }

    private static void vines(Window t, List<Pos> seeds, List<Finding> out) {
        List<Sample> samples = new ArrayList<>();
        for (Pos p : seeds) if (t.kind(p) == VINE) {
            int below = t.kind(p.offset(0, -1, 0));
            if (below == UNKNOWN || below == VINE) continue;
            // Explicit natural stone set, excluding cobble/brick decorations and unrelated solids.
            samples.add(new Sample(p, below == STONE || below == DEEPSLATE || below == HORIZONTAL_DEEPSLATE
                || below == BASALT || below == SMOOTH_BASALT, false));
        }
        for (List<Sample> group : populations(samples)) if (group.size() >= MIN_VINE_ENDINGS) {
            long grounded = group.stream().filter(Sample::first).count();
            if (grounded >= group.size() * GROUNDED_VINE_RATIO) {
                out.add(finding(Check.GROUNDED_VINES, group.getFirst().pos()));
                return;
            }
        }
    }

    private static void kelp(Window t, List<Pos> seeds, List<Finding> out) {
        List<Sample> samples = new ArrayList<>();
        for (Pos p : seeds) if (t.kind(p) == KELP) {
            int above = t.kind(p.offset(0, 1, 0));
            if (above == UNKNOWN || (above != AIR && above != WATER && above != FLOWING_WATER)) continue;
            if (t.kind(p.offset(0, -1, 0)) != KELP_PLANT || t.kind(p.offset(0, -2, 0)) != KELP_PLANT) continue;
            int waterSides = 0;
            for (int[] d : HORIZONTAL) {
                int k = t.kind(p.offset(d[0], -1, d[2]));
                if (k == WATER || k == FLOWING_WATER || k == KELP || k == KELP_PLANT) waterSides++;
            }
            if (waterSides < 2) continue;
            int age = age(t.cell(p));
            samples.add(new Sample(p, above == AIR, age >= 24 && age <= 25));
        }
        boolean surface = false, age = false;
        for (List<Sample> group : populations(samples)) if (group.size() >= MIN_KELP_TIPS) {
            if (!surface && group.stream().filter(Sample::first).count() >= group.size() * SURFACE_KELP_RATIO) {
                out.add(finding(Check.SURFACE_KELP, group.getFirst().pos())); surface = true;
            }
            if (!age && group.stream().filter(Sample::second).count() >= group.size() * MATURE_KELP_RATIO) {
                out.add(finding(Check.MATURE_KELP_AGE, group.getFirst().pos())); age = true;
            }
            if (surface && age) return;
        }
    }

    /** Local overlapping 24x48x24 populations. No quadratic all-to-all forest search. */
    private static List<List<Sample>> populations(List<Sample> samples) {
        Map<Pos, List<Sample>> buckets = new LinkedHashMap<>();
        for (Sample sample : samples) {
            Pos p = sample.pos();
            Pos bucket = new Pos(Math.floorDiv(p.x(), 8), Math.floorDiv(p.y(), 16), Math.floorDiv(p.z(), 8));
            buckets.computeIfAbsent(bucket, ignored -> new ArrayList<>()).add(sample);
        }
        List<List<Sample>> groups = new ArrayList<>();
        for (Pos origin : buckets.keySet()) {
            List<Sample> nearby = new ArrayList<>();
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                List<Sample> bucket = buckets.get(origin.offset(x, y, z));
                if (bucket != null) nearby.addAll(bucket);
            }
            groups.add(nearby);
        }
        return groups;
    }

    private static void geodes(Window t, List<Pos> seeds, List<Finding> out) {
        Set<Pos> visited = new HashSet<>();
        int features = 0;
        for (Pos seed : seeds) {
            if (!inner(t.kind(seed)) || visited.contains(seed)) continue;
            interrupted();
            if (++features > MAX_GEODES_PER_WINDOW) return;
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            List<Pos> cells = new ArrayList<>();
            queue.add(seed); visited.add(seed);
            boolean known = true, structure = false, overflow = false;
            int minX = seed.x(), maxX = minX, minY = seed.y(), maxY = minY, minZ = seed.z(), maxZ = minZ;
            while (!queue.isEmpty()) {
                Pos p = queue.removeFirst(); cells.add(p);
                if (cells.size() > MAX_GEODE_CELLS) { overflow = true; break; }
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
                for (int[] d : DIRECTIONS) {
                    Pos q = offset(p, d); int k = t.kind(q);
                    if (k == UNKNOWN) known = false;
                    if (k == STRUCTURE) structure = true;
                    if (inner(k) && visited.add(q)) queue.addLast(q);
                }
            }
            if (overflow || structure || cells.size() < MIN_GEODE_INNER) continue;
            // Coverage includes the entire shell and cavity; unknown means absence cannot be inferred.
            int width = maxX - minX + 1, height = maxY - minY + 1, depth = maxZ - minZ + 1;
            if (width < 4 || height < 4 || depth < 4 || width > 24 || height > 24 || depth > 24) continue;
            if (minX - 2 < t.minX || maxX + 2 >= t.maxX || minZ - 2 < t.minZ || maxZ + 2 >= t.maxZ
                    || minY - 2 < t.floorY() || maxY + 2 >= t.topY()) known = false;
            Set<Pos> calcite = new HashSet<>(), basalt = new HashSet<>(), crystals = new HashSet<>();
            int budding = 0, growthFaces = 0, attached = 0, mature = 0, cavity = 0;
            for (Pos p : cells) {
                boolean bud = t.kind(p) == BUDDING;
                if (bud) budding++;
                for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                    Pos q = offset(p, DIRECTIONS[direction]); int encoded = t.cell(q), k = kind(encoded);
                    if (k == CALCITE) {
                        calcite.add(q);
                        for (int[] d : DIRECTIONS) {
                            Pos r = offset(q, d);
                            if (t.kind(r) == SMOOTH_BASALT) basalt.add(r);
                        }
                    }
                    boolean correctCrystal = crystal(k) && facing(encoded) == direction;
                    if (correctCrystal && crystals.add(q)) { attached++; if (k == CLUSTER) mature++; }
                    if (bud && (k == AIR || k == WATER || correctCrystal)) growthFaces++;
                }
            }
            // This volume is bounded by 28^3, and evaluated only for promising connected geode material.
            for (int y = minY - 2; y <= maxY + 2; y++) for (int z = minZ - 2; z <= maxZ + 2; z++) for (int x = minX - 2; x <= maxX + 2; x++) {
                int k = t.kind(new Pos(x, y, z));
                if (k == UNKNOWN) known = false;
                if (k == STRUCTURE) structure = true;
                if (x > minX && x < maxX && y > minY && y < maxY && z > minZ && z < maxZ
                        && (k == AIR || k == WATER || crystal(k))) cavity++;
            }
            if (structure || calcite.size() < MIN_GEODE_CALCITE || basalt.size() < MIN_GEODE_BASALT || cavity < 8) continue;
            // Partial feature samples are rejected even for ratio checks: a clipped mature side is biased.
            if (!known) continue;
            if (attached >= MIN_GEODE_CRYSTALS && mature >= attached * MATURE_GEODE_RATIO) {
                out.add(finding(Check.MATURE_GEODE, seed));
            }
            int attachedToBudding = 0;
            for (Pos p : cells) if (t.kind(p) == BUDDING) for (int direction = 0; direction < DIRECTIONS.length; direction++) {
                int c = t.cell(offset(p, DIRECTIONS[direction]));
                if (crystal(kind(c)) && facing(c) == direction) attachedToBudding++;
            }
            if (growthFaces >= MIN_GROWTH_FACES && attachedToBudding <= growthFaces * SPARSE_CRYSTAL_RATIO) {
                out.add(finding(Check.SPARSE_GEODE_CRYSTALS, seed));
            }
            if (cells.size() >= MIN_STRIPPED_GEODE_INNER && budding <= cells.size() * MISSING_BUDDING_RATIO) {
                out.add(finding(Check.MISSING_BUDDING_AMETHYST, seed));
            }
        }
    }

    private static boolean soilType(int k) { return k == COARSE_DIRT || k == ROOTED_DIRT || k == PODZOL || k == MYCELIUM; }
    private static boolean naturalRock(int k) { return k == STONE || k == DEEPSLATE || k == HORIZONTAL_DEEPSLATE || k == BASALT || k == SMOOTH_BASALT; }
    private static boolean inner(int k) { return k == AMETHYST || k == BUDDING; }
    private static boolean crystal(int k) { return k >= SMALL_BUD && k <= CLUSTER; }
    private static Pos offset(Pos p, int[] direction) { return p.offset(direction[0], direction[1], direction[2]); }
    private static Finding finding(Check check, Pos p) { return new Finding(check, featureId(p), Math.floorDiv(p.x(), 16), Math.floorDiv(p.z(), 16)); }
    public static long chunkId(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    public static long featureId(Pos p) { return ((long) (p.x() & 0x3ffffff) << 38) | ((long) (p.z() & 0x3ffffff) << 12) | (p.y() & 0xfffL); }
    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }

    /** Prevents a supplied wider snapshot from accidentally admitting a tenth chunk. */
    private static final class Window implements Terrain {
        private final Terrain source;
        private final int minX, maxX, minZ, maxZ;
        private Window(Terrain source, int chunkX, int chunkZ) {
            this.source = source;
            minX = (chunkX - 1) << 4; maxX = (chunkX + 2) << 4;
            minZ = (chunkZ - 1) << 4; maxZ = (chunkZ + 2) << 4;
        }
        @Override public int cell(int x, int y, int z) {
            if (x < minX || x >= maxX || z < minZ || z >= maxZ || y < floorY() || y >= topY()) return UNKNOWN;
            return source.cell(x, y, z);
        }
        int cell(Pos p) { return cell(p.x(), p.y(), p.z()); }
        int kind(Pos p) { return SuspiciousAnalysis.kind(cell(p)); }
        @Override public int floorY() { return source.floorY(); }
        @Override public int topY() { return source.topY(); }
        @Override public int surfaceY(int x, int z) {
            if (x < minX || x >= maxX || z < minZ || z >= maxZ) return Integer.MIN_VALUE;
            return source.surfaceY(x, z);
        }
    }
}
