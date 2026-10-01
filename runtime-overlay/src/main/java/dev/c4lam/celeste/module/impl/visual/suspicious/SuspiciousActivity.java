package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
    public static final int MAX_OBSERVATIONS = 8192, MAX_EVIDENCE_CHUNKS = 512;
    public static final int MIN_EVIDENCE_CHUNKS = 4, MAX_CANDIDATES = 4;
    public static final int MAX_POINT_TESTS = 2_000_000, MAX_WITNESS_PAIRS = 8192;
    private static final double EPSILON = 1.0e-7;

    public enum Status { ESTIMATED, INSUFFICIENT, BROAD, INCONSISTENT, BUDGET_LIMIT }
    public record Candidate(int chunkX, int chunkZ) { }
    public record ScoredCandidate(Candidate candidate, Evaluation evaluation) { }
    public record Result(Status status, List<Candidate> candidates, int radiusChunks,
                         int evidenceChunks, int compatibleChunks) {
        public Result { candidates = List.copyOf(candidates); }
    }

    /**
     * Score actual feature locations around established activity estimates.
     * Positive evidence may lie anywhere in the configured activity radius;
     * only an open shaft within three chunks of this candidate can deduct 20.
     * There is no scoring-window-center fallback when fitting is inconclusive.
     */
    public static List<ScoredCandidate> score(Result estimate, Collection<Finding> actualFindings,
                                               Set<Check> enabled) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(actualFindings, "actual findings");
        Objects.requireNonNull(enabled, "enabled checks");
        if (estimate.status() != Status.ESTIMATED || estimate.candidates().isEmpty()) return List.of();
        if (estimate.radiusChunks() < MIN_RADIUS || estimate.radiusChunks() > MAX_RADIUS)
            throw new IllegalArgumentException("Activity radius must be 2 through 12 chunks");
        ArrayList<ScoredCandidate> scored = new ArrayList<>();
        for (Candidate candidate : estimate.candidates()) {
            interrupted();
            ArrayList<Finding> local = new ArrayList<>();
            for (Finding finding : actualFindings) {
                interrupted();
                if (!enabled.contains(finding.check())) continue;
                int radius = finding.check() == Check.OPEN_SHAFTS ? 3 : estimate.radiusChunks();
                long dx = (long) finding.chunkX() - candidate.chunkX();
                long dz = (long) finding.chunkZ() - candidate.chunkZ();
                // Axis checks also prevent overflow for arbitrary fixture coordinates.
                if (Math.abs(dx) <= radius && Math.abs(dz) <= radius && dx * dx + dz * dz <= radius * radius)
                    local.add(finding);
            }
            scored.add(new ScoredCandidate(candidate, SuspiciousAnalysis.score(local, enabled)));
        }
        return List.copyOf(scored);
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
     * At most 512 distinct chunks and 8192 raw observations are processed.
     * Each chunk supplies one actual qualified point, so forests/farms receive
     * no extra vote. Dropping other points in that chunk only broadens the fit.
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
            // Never silently discard a distant contradiction to produce a narrower answer.
            if (byChunk.size() > MAX_EVIDENCE_CHUNKS)
                return result(Status.BUDGET_LIMIT, radiusChunks, byChunk.size(), 0);
        }
        int count = byChunk.size();
        if (count < MIN_EVIDENCE_CHUNKS) return result(Status.INSUFFICIENT, radiusChunks, count, 0);
        List<GrowthObservation> points = List.copyOf(byChunk.values());
        double radius = radiusChunks * 16.0, radiusSquared = radius * radius;
        double lowX = Double.NEGATIVE_INFINITY, lowZ = Double.NEGATIVE_INFINITY;
        double highX = Double.POSITIVE_INFINITY, highZ = Double.POSITIVE_INFINITY;
        double minPointX = Double.POSITIVE_INFINITY, minPointZ = Double.POSITIVE_INFINITY;
        double maxPointX = Double.NEGATIVE_INFINITY, maxPointZ = Double.NEGATIVE_INFINITY;
        for (GrowthObservation point : points) {
            double x = point.blockX() + .5, z = point.blockZ() + .5;
            lowX = Math.max(lowX, x - radius); highX = Math.min(highX, x + radius);
            lowZ = Math.max(lowZ, z - radius); highZ = Math.min(highZ, z + radius);
            minPointX = Math.min(minPointX, x); maxPointX = Math.max(maxPointX, x);
            minPointZ = Math.min(minPointZ, z); maxPointZ = Math.max(maxPointZ, z);
        }
        if (lowX > highX || lowZ > highZ) return result(Status.INCONSISTENT, radiusChunks, count, 0);
        if (maxPointX - minPointX < radius || maxPointZ - minPointZ < radius)
            return result(Status.INSUFFICIENT, radiusChunks, count, 0);

        // Preserve uncertainty within each whole chunk. Testing only its center
        // can discard equally plausible chunks beside a boundary or corner.
        int minX = (int) Math.floor((lowX - EPSILON) / 16.0);
        int maxX = (int) Math.floor((highX + EPSILON) / 16.0);
        int minZ = (int) Math.floor((lowZ - EPSILON) / 16.0);
        int maxZ = (int) Math.floor((highZ + EPSILON) / 16.0);
        ArrayList<Candidate> compatible = new ArrayList<>();
        int compatibleCount = 0;
        Budget budget = new Budget();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            interrupted();
            double left = x * 16.0, bottom = z * 16.0;
            boolean possible = true;
            for (GrowthObservation point : points) {
                if (!budget.take()) return result(Status.BUDGET_LIMIT, radiusChunks, count, 0);
                double dx = distanceToInterval(point.blockX() + .5, left, left + 16.0);
                double dz = distanceToInterval(point.blockZ() + .5, bottom, bottom + 16.0);
                if (dx * dx + dz * dz > radiusSquared + EPSILON) { possible = false; break; }
            }
            if (possible) {
                compatibleCount++;
                if (compatible.size() < MAX_CANDIDATES) compatible.add(new Candidate(x, z));
            }
        }
        if (compatibleCount == 0) return result(Status.INCONSISTENT, radiusChunks, count, 0);
        if (compatibleCount > MAX_CANDIDATES) return result(Status.BROAD, radiusChunks, count, compatibleCount);
        int spanX = compatible.stream().mapToInt(Candidate::chunkX).max().orElseThrow()
            - compatible.stream().mapToInt(Candidate::chunkX).min().orElseThrow();
        int spanZ = compatible.stream().mapToInt(Candidate::chunkZ).max().orElseThrow()
            - compatible.stream().mapToInt(Candidate::chunkZ).min().orElseThrow();
        if (spanX > 1 || spanZ > 1) return result(Status.BROAD, radiusChunks, count, compatibleCount);
        // Separate circle/rectangle overlaps are only necessary conditions. Prove
        // one common disk-intersection point before labeling this an estimate.
        // All radii are equal: a nonempty intersection contains a disk center or
        // a pairwise boundary intersection. A failed numerical/work-limited
        // search stays inconclusive rather than inventing a point.
        if (!witness(points, radiusSquared, (lowX + highX) / 2, (lowZ + highZ) / 2, budget))
            return result(budget.exhausted ? Status.BUDGET_LIMIT : Status.INSUFFICIENT,
                radiusChunks, count, compatibleCount);
        // Every feasible chunk is reported; there is no forced ranking or top-four cut.
        compatible.sort(Comparator.comparingInt(Candidate::chunkX).thenComparingInt(Candidate::chunkZ));
        return new Result(Status.ESTIMATED, compatible, radiusChunks, count, compatibleCount);
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
    private static double distanceToInterval(double point, double low, double high) {
        return point < low ? low - point : Math.max(0, point - high);
    }
    private static boolean witness(List<GrowthObservation> points, double radiusSquared,
                                   double middleX, double middleZ, Budget budget) {
        if (insideAll(points, middleX, middleZ, radiusSquared, budget)) return true;
        for (GrowthObservation point : points) {
            if (budget.exhausted) return false;
            if (insideAll(points, point.blockX() + .5, point.blockZ() + .5, radiusSquared, budget)) return true;
        }
        int pairs = 0;
        for (int i = 0; i < points.size(); i++) for (int j = i + 1; j < points.size(); j++) {
            interrupted();
            if (budget.exhausted || ++pairs > MAX_WITNESS_PAIRS) { budget.exhausted = true; return false; }
            var a = points.get(i); var b = points.get(j);
            double dx = (double) b.blockX() - a.blockX(), dz = (double) b.blockZ() - a.blockZ();
            double distanceSquared = dx * dx + dz * dz;
            if (distanceSquared == 0 || distanceSquared > 4 * radiusSquared) continue;
            double half = Math.sqrt(Math.max(0, radiusSquared - distanceSquared / 4) / distanceSquared);
            double x = a.blockX() + .5 + dx / 2, z = a.blockZ() + .5 + dz / 2;
            if (insideAll(points, x - dz * half, z + dx * half, radiusSquared, budget)
                || insideAll(points, x + dz * half, z - dx * half, radiusSquared, budget)) return true;
        }
        return false;
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
    }
    private static Result result(Status status, int radius, int evidence, int compatible) {
        return new Result(status, List.of(), radius, evidence, compatible);
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Activity fit cancelled");
    }
}
