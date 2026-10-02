package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.GrowthObservation;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.Check;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.Evaluation;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.Finding;

/**
 * Positive-only fit of a user-selected circular activity approximation.
 * Vanilla 1.21.11 random ticks follow ticking tickets, including non-player
 * loaders, so these are possible activity centers, never proof of a base.
 * No absent, unloaded, young or unobserved terrain is treated as negative data.
 */
public final class SuspiciousActivity {
    private SuspiciousActivity() { }

    public static final int DEFAULT_RADIUS = 7, MIN_RADIUS = 2, MAX_RADIUS = 12;
    public static final int MAX_OBSERVATIONS = 8192;
    public static final int MAX_EVIDENCE_CHUNKS = (4 * MAX_RADIUS + 1) * (4 * MAX_RADIUS + 1);
    public static final int MIN_EVIDENCE_CHUNKS = 1;
    // This is a work bound, not a cap on the number of displayed answers. The
    // caller's local +/-2R evidence window expands to at most this grid size.
    public static final int MAX_GRID_CHUNKS = (6 * MAX_RADIUS + 3) * (6 * MAX_RADIUS + 3);
    public static final int MAX_POINT_TESTS = 2_000_000, MAX_WITNESS_PAIRS = 8192;
    public static final int MAX_SCORING_FINDINGS = 65_536;
    // The caller retains a two-chunk margin outside the fitter's +/-2R window.
    public static final int MAX_SCORING_CHUNKS = (4 * MAX_RADIUS + 5) * (4 * MAX_RADIUS + 5);
    private static final double EPSILON = 1.0e-7;

    public enum Status { ESTIMATED, INSUFFICIENT, BROAD, INCONSISTENT, BUDGET_LIMIT }
    public enum Origin { GROWTH_ESTIMATE, LOCAL_EVIDENCE }
    public record Candidate(int chunkX, int chunkZ) { }
    public record ScoredCandidate(Candidate candidate, Evaluation evaluation, Origin origin) {
        public ScoredCandidate(Candidate candidate, Evaluation evaluation) {
            this(candidate, evaluation, Origin.GROWTH_ESTIMATE);
        }
    }
    public record Result(Status status, List<Candidate> candidates, int radiusChunks,
                         int evidenceChunks, int compatibleChunks) {
        public Result { candidates = List.copyOf(candidates); }
    }

    /**
     * Score actual feature locations around established activity estimates.
     * Positive evidence may lie anywhere in the configured activity radius;
     * only an open shaft within three chunks of this candidate can deduct 20.
     * Estimates use actual growth positions, never scoring-window centers.
     */
    public static List<ScoredCandidate> score(Result estimate, Collection<Finding> actualFindings,
                                               Set<Check> enabled) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(actualFindings, "actual findings");
        Objects.requireNonNull(enabled, "enabled checks");
        if (estimate.status() != Status.ESTIMATED || estimate.candidates().isEmpty()) return List.of();
        if (estimate.radiusChunks() < MIN_RADIUS || estimate.radiusChunks() > MAX_RADIUS)
            throw new IllegalArgumentException("Activity radius must be 2 through 12 chunks");
        return scoreGrowth(estimate, indexFindings(actualFindings, enabled), enabled);
    }

    private static LinkedHashMap<Long, FindingChunk> indexFindings(Collection<Finding> actualFindings,
                                                                  Set<Check> enabled) {
        return indexFindings(actualFindings, enabled, null);
    }

    private static LinkedHashMap<Long, FindingChunk> indexFindings(Collection<Finding> actualFindings,
                                                                  Set<Check> enabled, Budget budget) {
        // Several checks and overlapping windows can describe the same chunk.
        // Filter/deduplicate once, then do one distance test per occupied chunk
        // per candidate instead of repeating it for every individual finding.
        LinkedHashMap<Long, FindingChunk> chunks = new LinkedHashMap<>();
        LinkedHashSet<Finding> unique = new LinkedHashSet<>();
        for (Finding finding : actualFindings) {
            interrupted();
            if (budget != null && !budget.take()) return null;
            if (!enabled.contains(finding.check()) || !unique.add(finding)) continue;
            long key = (long) finding.chunkX() << 32 | finding.chunkZ() & 0xffffffffL;
            FindingChunk chunk = chunks.computeIfAbsent(key,
                unused -> new FindingChunk(finding.chunkX(), finding.chunkZ()));
            (finding.check() == Check.OPEN_SHAFTS ? chunk.shafts : chunk.positive).add(finding);
        }
        return chunks;
    }

    private static List<ScoredCandidate> scoreGrowth(Result estimate, LinkedHashMap<Long, FindingChunk> chunks,
                                                     Set<Check> enabled) {
        return scoreGrowth(estimate, chunks, enabled, null);
    }

    private static List<ScoredCandidate> scoreGrowth(Result estimate, LinkedHashMap<Long, FindingChunk> chunks,
                                                     Set<Check> enabled, Budget budget) {
        if (estimate.status() != Status.ESTIMATED || estimate.candidates().isEmpty()) return List.of();
        int radius = estimate.radiusChunks(), maximumRadius = Math.max(3, radius);
        int positiveDistance = radius * radius;
        ArrayList<ScoredCandidate> scored = new ArrayList<>();
        for (Candidate candidate : estimate.candidates()) {
            interrupted();
            if (budget != null && !budget.take()) return List.of();
            ArrayList<Finding> local = new ArrayList<>();
            for (FindingChunk chunk : chunks.values()) {
                interrupted();
                if (budget != null && !budget.take()) return List.of();
                long dx = (long) chunk.x - candidate.chunkX();
                long dz = (long) chunk.z - candidate.chunkZ();
                // Axis checks also prevent overflow for arbitrary fixture coordinates.
                if (Math.abs(dx) > maximumRadius || Math.abs(dz) > maximumRadius) continue;
                long distance = dx * dx + dz * dz;
                if (distance <= positiveDistance && !append(local, chunk.positive, budget)) return List.of();
                if (distance <= 9 && !append(local, chunk.shafts, budget)) return List.of();
            }
            scored.add(new ScoredCandidate(candidate, SuspiciousAnalysis.score(local, enabled)));
        }
        return List.copyOf(scored);
    }

    private static boolean append(List<Finding> target, List<Finding> findings, Budget budget) {
        // Charge both collecting each finding and its later fixed-weight score traversal.
        if (budget != null && !budget.reserve(findings.size() * 2)) return false;
        target.addAll(findings);
        return true;
    }

    /**
     * Adds candidates at actual observed land disturbances, even without plant growth.
     * These candidates gather positives only from their immediate 3x3 neighborhood;
     * they never create a ticking disk or mark unrelated scoring-window centers.
     * A shared coordinate keeps its higher whole evaluation, with growth winning ties.
     * Work-limit exhaustion returns no partial ranking. All inputs are immutable worker data.
     */
    public static List<ScoredCandidate> scoreWithLand(Result growthEstimate, Collection<Finding> actualFindings,
                                                      Set<Check> enabled) {
        Objects.requireNonNull(growthEstimate, "growth estimate");
        Objects.requireNonNull(actualFindings, "actual findings");
        Objects.requireNonNull(enabled, "enabled checks");
        interrupted();
        if (growthEstimate.radiusChunks() < MIN_RADIUS || growthEstimate.radiusChunks() > MAX_RADIUS)
            throw new IllegalArgumentException("Activity radius must be 2 through 12 chunks");
        if (actualFindings.size() > MAX_SCORING_FINDINGS || growthEstimate.candidates().size() > MAX_GRID_CHUNKS)
            return List.of();
        Budget budget = new Budget();
        ArrayList<Finding> ordered = new ArrayList<>();
        int visited = 0;
        for (Finding finding : actualFindings) {
            interrupted();
            if (!budget.take()) return List.of();
            if (++visited > MAX_SCORING_FINDINGS) return List.of();
            if (enabled.contains(finding.check())) ordered.add(finding);
        }
        ordered.sort(Comparator.comparingInt(Finding::chunkX).thenComparingInt(Finding::chunkZ)
            .thenComparingInt(f -> f.check().ordinal()).thenComparingLong(Finding::featureId));
        LinkedHashMap<Long, FindingChunk> chunks = indexFindings(ordered, enabled, budget);
        if (chunks == null || chunks.size() > MAX_SCORING_CHUNKS) return List.of();
        LinkedHashMap<Candidate, ScoredCandidate> combined = new LinkedHashMap<>();
        List<ScoredCandidate> growth = scoreGrowth(growthEstimate, chunks, enabled, budget);
        if (budget.exhausted) return List.of();
        for (ScoredCandidate candidate : growth)
            combined.put(candidate.candidate(), candidate);
        for (FindingChunk chunk : chunks.values()) {
            interrupted();
            if (!budget.reserve(chunk.positive.size() + 1)) return List.of();
            if (chunk.positive.stream().noneMatch(f -> landCheck(f.check()))) continue;
            Candidate candidate = new Candidate(chunk.x, chunk.z);
            ArrayList<Finding> local = new ArrayList<>();
            // Nine positive lookups plus the 29 cells in a radius-three shaft disk.
            // No scan across the whole evidence map for every land candidate.
            for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
                if (!budget.take()) return List.of();
                long cx = (long) chunk.x + x, cz = (long) chunk.z + z;
                if (cx < Integer.MIN_VALUE || cx > Integer.MAX_VALUE || cz < Integer.MIN_VALUE || cz > Integer.MAX_VALUE) continue;
                FindingChunk near = chunks.get(cx << 32 | cz & 0xffffffffL);
                if (near == null) continue;
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1 && !append(local, near.positive, budget)) return List.of();
                if (x * x + z * z <= 9 && !append(local, near.shafts, budget)) return List.of();
            }
            ScoredCandidate land = new ScoredCandidate(candidate, SuspiciousAnalysis.score(local, enabled), Origin.LOCAL_EVIDENCE);
            ScoredCandidate previous = combined.get(candidate);
            if (previous == null || land.evaluation().score() > previous.evaluation().score()) combined.put(candidate, land);
        }
        ArrayList<ScoredCandidate> result = new ArrayList<>(combined.values());
        result.sort(Comparator.comparingInt((ScoredCandidate c) -> c.candidate().chunkX())
            .thenComparingInt(c -> c.candidate().chunkZ()));
        return List.copyOf(result);
    }

    private static boolean landCheck(Check check) {
        return check == Check.ROTATED_DEEPSLATE || check == Check.MISMATCHED_SOIL
            || check == Check.MISSING_BUDDING_AMETHYST || check == Check.STRIPPED_GEODE
            || check == Check.BURIED_ACCESS_PATH;
    }

    private static final class FindingChunk {
        final int x, z;
        final List<Finding> positive = new ArrayList<>(), shafts = new ArrayList<>();
        FindingChunk(int x, int z) { this.x = x; this.z = z; }
    }

    /** Stable representative of one evidence chunk; suitable for a bounded session map. */
    public static GrowthObservation representative(GrowthObservation a, GrowthObservation b) {
        Objects.requireNonNull(a, "first observation");
        Objects.requireNonNull(b, "second observation");
        if (chunkX(a) != chunkX(b) || chunkZ(a) != chunkZ(b))
            throw new IllegalArgumentException("Representatives must belong to the same chunk");
        int order = Double.compare(centerDistance(a), centerDistance(b));
        if (order == 0) order = Integer.compare(a.blockX(), b.blockX());
        if (order == 0) order = Integer.compare(a.blockZ(), b.blockZ());
        if (order == 0) order = Integer.compare(a.blockY(), b.blockY());
        if (order == 0) order = a.check().compareTo(b.check());
        if (order == 0) order = Long.compareUnsigned(a.sampleId(), b.sampleId());
        return order <= 0 ? a : b;
    }

    /**
     * At most one full local +/-2R window and 8192 raw observations are processed.
     * Each chunk supplies one actual qualified point, so forests/farms receive
     * no extra vote. Every center with the best observed support is retained.
     * Call on the immutable-analysis worker, not the Minecraft/render thread.
     */
    public static Result estimate(Collection<GrowthObservation> observations, int radiusChunks) {
        Objects.requireNonNull(observations, "observations");
        if (radiusChunks < MIN_RADIUS || radiusChunks > MAX_RADIUS)
            throw new IllegalArgumentException("Activity radius must be 2 through 12 chunks");
        if (observations.size() > MAX_OBSERVATIONS)
            return result(Status.BUDGET_LIMIT, radiusChunks, 0, 0);
        TreeMap<Long, GrowthObservation> byChunk = new TreeMap<>();
        int visited = 0;
        for (GrowthObservation observation : observations) {
            interrupted();
            if (++visited > MAX_OBSERVATIONS)
                return result(Status.BUDGET_LIMIT, radiusChunks, byChunk.size(), 0);
            Objects.requireNonNull(observation, "observation");
            if (!growthCheck(observation)) continue;
            long key = (long) chunkX(observation) << 32 | chunkZ(observation) & 0xffffffffL;
            byChunk.merge(key, observation, SuspiciousActivity::representative);
            // Never silently discard evidence to make a partial ranking look complete.
            if (byChunk.size() > MAX_EVIDENCE_CHUNKS)
                return result(Status.BUDGET_LIMIT, radiusChunks, byChunk.size(), 0);
        }
        int count = byChunk.size();
        if (count < MIN_EVIDENCE_CHUNKS) return result(Status.INSUFFICIENT, radiusChunks, count, 0);
        List<GrowthObservation> points = List.copyOf(byChunk.values());
        double radius = radiusChunks * 16.0, radiusSquared = radius * radius;
        double lowX = Double.NEGATIVE_INFINITY, lowZ = Double.NEGATIVE_INFINITY;
        double highX = Double.POSITIVE_INFINITY, highZ = Double.POSITIVE_INFINITY;
        TreeMap<Long, Integer> support = new TreeMap<>();
        Budget budget = new Budget();
        int maximumSupport = 0;
        for (GrowthObservation point : points) {
            double x = point.blockX() + .5, z = point.blockZ() + .5;
            lowX = Math.max(lowX, x - radius); highX = Math.min(highX, x + radius);
            lowZ = Math.max(lowZ, z - radius); highZ = Math.min(highZ, z + radius);

            // Each qualified evidence chunk casts one vote for every candidate
            // center inside its radius. Walking each small disk avoids scanning
            // the potentially enormous empty gap between distant observations.
            int minX = (int) Math.ceil((x - radius - 8.0) / 16.0);
            int maxX = (int) Math.floor((x + radius - 8.0) / 16.0);
            int minZ = (int) Math.ceil((z - radius - 8.0) / 16.0);
            int maxZ = (int) Math.floor((z + radius - 8.0) / 16.0);
            for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
                if (!budget.take()) return result(Status.BUDGET_LIMIT, radiusChunks, count, 0);
                double dx = cx * 16.0 + 8.0 - x, dz = cz * 16.0 + 8.0 - z;
                if (dx * dx + dz * dz > radiusSquared + EPSILON) continue;
                long key = (long) cx << 32 | cz & 0xffffffffL;
                Integer previous = support.get(key);
                if (previous == null && support.size() >= MAX_GRID_CHUNKS)
                    return result(Status.BUDGET_LIMIT, radiusChunks, count, 0);
                int votes = previous == null ? 1 : previous + 1;
                support.put(key, votes);
                maximumSupport = Math.max(maximumSupport, votes);
            }
        }

        // A small common intersection may sit between chunk centers. Prefer an
        // exact all-evidence witness over a weaker grid fit, preserving answers
        // on chunk edges and corners without inventing circle-overlap midpoints.
        if (maximumSupport < count && lowX <= highX && lowZ <= highZ) {
            // Interior observations remain votes above, but add no constraint
            // to this exact intersection: distance to a fixed center is convex.
            // Keeping only hull vertices avoids quadratic redundant pair tests
            // exhausting the witness budget on a densely observed area.
            List<GrowthObservation> boundary = convexHull(points, budget);
            Witness shared = budget.exhausted ? null
                : witness(boundary, radiusSquared, (lowX + highX) / 2, (lowZ + highZ) / 2, budget);
            if (budget.exhausted) return result(Status.BUDGET_LIMIT, radiusChunks, count, 0);
            if (shared != null) {
                ArrayList<Candidate> touching = new ArrayList<>();
                int minX = (int) Math.floor((shared.x - EPSILON) / 16.0);
                int maxX = (int) Math.floor((shared.x + EPSILON) / 16.0);
                int minZ = (int) Math.floor((shared.z - EPSILON) / 16.0);
                int maxZ = (int) Math.floor((shared.z + EPSILON) / 16.0);
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                    touching.add(new Candidate(x, z));
                return new Result(Status.ESTIMATED, touching, radiusChunks, count, touching.size());
            }
        }

        ArrayList<Candidate> best = new ArrayList<>();
        for (var entry : support.entrySet()) {
            if (!budget.take()) return result(Status.BUDGET_LIMIT, radiusChunks, count, 0);
            if (entry.getValue() == maximumSupport)
                best.add(new Candidate((int) (entry.getKey() >> 32), (int) (long) entry.getKey()));
        }
        best.sort(Comparator.comparingInt(Candidate::chunkX).thenComparingInt(Candidate::chunkZ));
        return new Result(Status.ESTIMATED, best, radiusChunks, count, best.size());
    }

    private static boolean growthCheck(GrowthObservation point) {
        return switch (point.check()) {
            case GROUNDED_VINES, MATURE_GEODE, SURFACE_KELP, MATURE_KELP_AGE -> true;
            default -> false;
        };
    }
    private static int chunkX(GrowthObservation point) { return Math.floorDiv(point.blockX(), 16); }
    private static int chunkZ(GrowthObservation point) { return Math.floorDiv(point.blockZ(), 16); }
    private static double centerDistance(GrowthObservation point) {
        double dx = Math.floorMod(point.blockX(), 16) - 7.5;
        double dz = Math.floorMod(point.blockZ(), 16) - 7.5;
        return dx * dx + dz * dz;
    }
    private record Witness(double x, double z) { }
    private static List<GrowthObservation> convexHull(List<GrowthObservation> points, Budget budget) {
        if (points.size() <= 2) return points;
        ArrayList<GrowthObservation> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparingInt(GrowthObservation::blockX).thenComparingInt(GrowthObservation::blockZ));
        ArrayList<GrowthObservation> hull = new ArrayList<>();
        for (GrowthObservation point : ordered) {
            if (!budget.take()) return List.of();
            while (hull.size() >= 2 && turn(hull.get(hull.size() - 2), hull.getLast(), point) <= 0)
                hull.removeLast();
            hull.add(point);
        }
        int lower = hull.size();
        for (int i = ordered.size() - 2; i >= 0; i--) {
            if (!budget.take()) return List.of();
            GrowthObservation point = ordered.get(i);
            while (hull.size() > lower && turn(hull.get(hull.size() - 2), hull.getLast(), point) <= 0)
                hull.removeLast();
            hull.add(point);
        }
        hull.removeLast();
        return hull;
    }
    private static long turn(GrowthObservation a, GrowthObservation b, GrowthObservation c) {
        // Called only after overlapping radius bounds establish that both axis
        // spans are <=384 blocks, so these exact integer products cannot overflow.
        return ((long) b.blockX() - a.blockX()) * ((long) c.blockZ() - a.blockZ())
            - ((long) b.blockZ() - a.blockZ()) * ((long) c.blockX() - a.blockX());
    }
    private static Witness witness(List<GrowthObservation> points, double radiusSquared,
                                   double middleX, double middleZ, Budget budget) {
        if (insideAll(points, middleX, middleZ, radiusSquared, budget)) return new Witness(middleX, middleZ);
        for (GrowthObservation point : points) {
            if (budget.exhausted) return null;
            if (insideAll(points, point.blockX() + .5, point.blockZ() + .5, radiusSquared, budget))
                return new Witness(point.blockX() + .5, point.blockZ() + .5);
        }
        int pairs = 0;
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            interrupted();
            if (budget.exhausted || ++pairs > MAX_WITNESS_PAIRS) { budget.exhausted = true; return null; }
            var a = points.get(i); var b = points.get(j);
            double dx = (double) b.blockX() - a.blockX(), dz = (double) b.blockZ() - a.blockZ();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared == 0 || distanceSquared > 4 * radiusSquared) continue;
            double half = Math.sqrt(Math.max(0, radiusSquared - distanceSquared / 4) / distanceSquared);
            double x = a.blockX() + .5 + dx / 2, z = a.blockZ() + .5 + dz / 2;
            if (insideAll(points, x - dz * half, z + dx * half, radiusSquared, budget))
                return new Witness(x - dz * half, z + dx * half);
            if (insideAll(points, x + dz * half, z - dx * half, radiusSquared, budget))
                return new Witness(x + dz * half, z - dx * half);
        }
        return null;
    }
    private static boolean insideAll(List<GrowthObservation> points, double x, double z,
                                     double radiusSquared, Budget budget) {
        for (GrowthObservation point : points) {
            if (!budget.take()) return false;
            double dx = point.blockX() + .5 - x, dz = point.blockZ() + .5 - z;
            if (dx * dx + dz * dz > radiusSquared + EPSILON) return false;
        }
        return true;
    }
    private static final class Budget {
        int tests;
        boolean exhausted;
        boolean take() {
            if ((tests & 63) == 0) interrupted();
            if (exhausted || tests >= MAX_POINT_TESTS) { exhausted = true; return false; }
            tests++; return true;
        }
        boolean reserve(int visits) {
            interrupted();
            if (exhausted || visits < 0 || visits > MAX_POINT_TESTS - tests) { exhausted = true; return false; }
            tests += visits; return true;
        }
    }
    private static Result result(Status status, int radius, int evidence, int compatible) {
        return new Result(status, List.of(), radius, evidence, compatible);
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Activity fit cancelled");
    }
}
