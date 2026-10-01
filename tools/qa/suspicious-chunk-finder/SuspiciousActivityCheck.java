package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousActivity.*;

/** Exercises the actual estimator without Minecraft, rendered substitutes or network input. */
public final class SuspiciousActivityCheck {
    private static int cases, assertions;
    public static void main(String[] args) {
        run("default radius is seven; supported range is two to twelve", () -> {
            check(DEFAULT_RADIUS == 7 && MIN_RADIUS == 2 && MAX_RADIUS == 12);
            throwsType(IllegalArgumentException.class, () -> estimate(List.of(), 1));
            throwsType(IllegalArgumentException.class, () -> estimate(List.of(), 13));
        });
        run("empty evidence waits; a qualified local population can offer many locations", () -> {
            check(estimate(List.of(), 7).status() == Status.INSUFFICIENT);
            List<GrowthObservation> farm = new ArrayList<>();
            for (int i = 0; i < 4096; i++) farm.add(point(i & 15, (i >> 4) & 15));
            Result result = estimate(farm, 7);
            check(result.evidenceChunks() == 1 && result.status() == Status.ESTIMATED);
            check(result.candidates().size() > 4);
        });
        run("balanced boundary evidence narrows a center without forcing four markers", () -> {
            Result result = estimate(compass(8, 8, 7), 7);
            check(result.status() == Status.ESTIMATED);
            check(result.candidates().equals(List.of(new Candidate(0, 0))));
        });
        run("a center on a chunk edge preserves both plausible chunks", () -> {
            Result result = estimate(compass(0, 8, 7), 7);
            check(result.status() == Status.ESTIMATED);
            check(new HashSet<>(result.candidates()).equals(Set.of(new Candidate(-1, 0), new Candidate(0, 0))));
        });
        run("a center on a chunk corner preserves all four plausible chunks", () -> {
            Result result = estimate(compass(0, 0, 7), 7);
            check(result.status() == Status.ESTIMATED);
            check(new HashSet<>(result.candidates()).equals(Set.of(new Candidate(-1, -1), new Candidate(-1, 0),
                new Candidate(0, -1), new Candidate(0, 0))));
        });
        run("broad positive evidence keeps every best supported chunk", () -> {
            Result result = estimate(compass(8, 8, 4), 7);
            check(result.status() == Status.ESTIMATED && result.compatibleChunks() > 4);
            check(result.candidates().size() == result.compatibleChunks());
            assertBestCenters(compass(8, 8, 4), 7, result);
        });
        run("one-sided and collinear evidence can produce locations", () -> {
            check(estimate(List.of(point(-112, 0), point(-64, 0), point(64, 0), point(112, 0)), 7)
                .status() == Status.ESTIMATED);
            check(estimate(List.of(point(0, 0), point(16, 16), point(32, 32), point(48, 48)), 7)
                .candidates().size() > 4);
        });
        run("qualified evidence spanning four chunk corners does not need axis spread", () -> {
            Result result = estimate(List.of(point(5, 5), point(25, 5), point(5, 25), point(25, 25)), 2);
            check(result.evidenceChunks() == 4 && result.status() == Status.ESTIMATED);
            check(!result.candidates().isEmpty());
        });
        run("conflicting footprints use real maximum support instead of inventing a shared center", () -> {
            List<GrowthObservation> data = List.of(point(0, 0), point(200, 0), point(100, 173), point(100, 156));
            Result result = estimate(data, 7);
            check(result.status() == Status.ESTIMATED && !result.candidates().isEmpty());
            assertBestCenters(data, 7, result);
        });
        run("dense conflicting footprints retain the completed best fit instead of exhausting redundant witness pairs", () -> {
            // This triangle has no common radius-192 disk center. Interior
            // evidence supplies real votes but does not change its boundary.
            List<GrowthObservation> data = new ArrayList<>(List.of(point(0, 0), point(340, 0), point(170, 294)));
            for (int z = 8; z < 294; z += 16) for (int x = 8; x < 340; x += 16) {
                if (x < z * 170.0 / 294 || x > 340 - z * 170.0 / 294) continue;
                int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                if ((cx == 0 && cz == 0) || (cx == 21 && cz == 0) || (cx == 10 && cz == 18)) continue;
                data.add(point(x, z));
            }
            check(data.size() > 128);
            Result result = estimate(data, 12);
            check(result.status() == Status.ESTIMATED && !result.candidates().isEmpty());
            assertBestCenters(data, 12, result);
            Collections.reverse(data);
            check(estimate(data, 12).equals(result));
        });
        run("an isolated distant outlier cannot block the stronger local footprint", () -> {
            List<GrowthObservation> evidence = new ArrayList<>(compass(8, 8, 4));
            evidence.add(point(1024, 1024));
            Result result = estimate(evidence, 7);
            check(result.status() == Status.ESTIMATED);
            check(result.candidates().stream().allMatch(c -> Math.abs(c.chunkX()) < 8 && Math.abs(c.chunkZ()) < 8));
            assertBestCenters(evidence, 7, result);
        });
        run("changed radius adjusts support without suppressing all locations", () -> {
            check(estimate(compass(8, 8, 7), 6).status() == Status.ESTIMATED);
            Result large = estimate(compass(8, 8, 7), 8);
            check(large.status() == Status.ESTIMATED && large.candidates().size() > 4);
        });
        run("equally supported separated regions are both kept with no marker count cap", () -> {
            List<GrowthObservation> evidence = new ArrayList<>(compass(8, 8, 4));
            evidence.addAll(compass(520, 8, 4));
            Result result = estimate(evidence, 7);
            check(result.status() == Status.ESTIMATED && result.candidates().size() > 4);
            check(result.candidates().stream().anyMatch(c -> c.chunkX() < 8));
            check(result.candidates().stream().anyMatch(c -> c.chunkX() > 24));
            assertBestCenters(evidence, 7, result);
        });
        run("negative coordinates and translation across the world preserve geometry", () -> {
            for (int offset : new int[]{-16000, 16000, -29_000_000, 29_000_000}) {
                Result result = estimate(compass(offset + 8, offset + 8, 7), 7);
                check(result.candidates().equals(List.of(new Candidate(Math.floorDiv(offset, 16), Math.floorDiv(offset, 16)))));
            }
        });
        run("input order and repeated overlapping windows cannot weight the result", () -> {
            List<GrowthObservation> data = new ArrayList<>(compass(8, 8, 7));
            Result expected = estimate(data, 7);
            for (int i = 0; i < 100; i++) data.addAll(compass(8, 8, 7));
            Collections.reverse(data);
            check(estimate(data, 7).equals(expected));
        });
        run("a representative is an actual stable point from the evidence chunk", () -> {
            GrowthObservation edge = point(-16, -16), center = point(-9, -9), tie = point(-8, -8);
            check(representative(edge, center).equals(center));
            check(representative(tie, center).equals(center));
            check(representative(center, tie).equals(center));
            throwsType(IllegalArgumentException.class, () -> representative(center, point(0, 0)));
        });
        run("disturbance and window-score locations are excluded from growth fitting", () -> {
            List<GrowthObservation> data = new ArrayList<>();
            for (GrowthObservation p : compass(8, 8, 7)) data.add(new GrowthObservation(Check.MISSING_BUDDING_AMETHYST,
                p.sampleId(), p.blockX(), p.blockY(), p.blockZ()));
            check(estimate(data, 7).status() == Status.INSUFFICIENT);
        });
        run("over-budget evidence fails without silently selecting convenient points", () -> {
            List<GrowthObservation> data = new ArrayList<>();
            for (int i = 0; i <= MAX_EVIDENCE_CHUNKS; i++) data.add(point(i * 16, 0));
            check(estimate(data, 7).status() == Status.BUDGET_LIMIT);
            check(estimate(Collections.nCopies(8193, point(0, 0)), 7).status() == Status.BUDGET_LIMIT);
        });
        run("all qualified chunks in the largest local window fit without the old 512-chunk cutoff", () -> {
            List<GrowthObservation> data = new ArrayList<>();
            for (int x = -2 * MAX_RADIUS; x <= 2 * MAX_RADIUS; x++)
                for (int z = -2 * MAX_RADIUS; z <= 2 * MAX_RADIUS; z++) data.add(point(x * 16 + 8, z * 16 + 8));
            check(data.size() == MAX_EVIDENCE_CHUNKS && data.size() > 512);
            Result result = estimate(data, MAX_RADIUS);
            check(result.status() == Status.ESTIMATED && !result.candidates().isEmpty());
            check(result.evidenceChunks() == data.size());
            Collections.reverse(data);
            check(estimate(data, MAX_RADIUS).equals(result));
        });
        run("results are immutable and worker cancellation is honored", () -> {
            Result result = estimate(compass(8, 8, 7), 7);
            throwsType(UnsupportedOperationException.class, () -> result.candidates().clear());
            Thread.currentThread().interrupt();
            try { throwsType(CancellationException.class, () -> estimate(compass(8, 8, 7), 7)); }
            finally { Thread.interrupted(); }
        });
        run("only an actual estimate can receive evidence or a shaft deduction", () -> {
            List<Finding> evidence = List.of(finding(Check.BURIED_ACCESS_PATH, 0, 0), finding(Check.OPEN_SHAFTS, 0, 0));
            for (Status status : Status.values()) if (status != Status.ESTIMATED) {
                check(SuspiciousActivity.score(new Result(status, List.of(), 7, 4, 0), evidence, allChecks()).isEmpty());
            }
        });
        run("shaft deduction includes exactly three chunks but excludes four and diagonal corners", () -> {
            Result fit = fitted(0, 0, 7);
            for (int[] shaft : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}})
                check(scored(fit, List.of(finding(Check.BURIED_ACCESS_PATH, 0, 0),
                    finding(Check.OPEN_SHAFTS, shaft[0], shaft[1])), allChecks()).score() == 15);
            for (int[] shaft : new int[][]{{4, 0}, {0, -4}, {3, 1}, {-3, -1}, {3, 3}}) {
                Evaluation evaluation = scored(fit, List.of(finding(Check.BURIED_ACCESS_PATH, 0, 0),
                    finding(Check.OPEN_SHAFTS, shaft[0], shaft[1])), allChecks());
                check(evaluation.score() == 35 && !evaluation.contributions().containsKey(Check.OPEN_SHAFTS));
            }
        });
        run("positive footprint evidence can qualify an empty center outside the old nine chunks", () -> {
            Evaluation evaluation = scored(fitted(0, 0, 7), List.of(finding(Check.ROTATED_DEEPSLATE, 7, 0),
                finding(Check.MISMATCHED_SOIL, 0, -7)), allChecks());
            check(evaluation.score() == 40 && evaluation.qualifies(35));
            check(evaluation.findings().stream().noneMatch(f -> f.chunkX() == 0 && f.chunkZ() == 0));
            check(scored(fitted(0, 0, 6), evaluation.findings(), allChecks()).score() == 0);
            check(scored(fitted(0, 0, 7), List.of(finding(Check.BURIED_ACCESS_PATH, 7, 1)), allChecks()).score() == 0);
        });
        run("actual finding coordinates control each candidate's independent shaft penalty", () -> {
            Result fit = new Result(Status.ESTIMATED, List.of(new Candidate(0, 0), new Candidate(1, 0)), 7, 4, 2);
            var scores = SuspiciousActivity.score(fit, List.of(finding(Check.BURIED_ACCESS_PATH, 0, 0),
                finding(Check.OPEN_SHAFTS, -3, 0)), allChecks());
            check(scores.get(0).evaluation().score() == 15 && scores.get(1).evaluation().score() == 35);
        });
        run("fixed weights caps toggles threshold and one shaft deduction remain intact", () -> {
            List<Finding> evidence = new ArrayList<>();
            for (Check check : Check.values()) for (int i = 0; i < 4; i++) evidence.add(new Finding(check, i, i % 2, 0));
            Evaluation evaluation = scored(fitted(0, 0, 7), evidence, allChecks());
            check(evaluation.score() == 110 && evaluation.contributions().get(Check.OPEN_SHAFTS) == -20);
            check(evaluation.contributions().getOrDefault(Check.MISSING_BUDDING_AMETHYST, 0)
                + evaluation.contributions().getOrDefault(Check.MATURE_GEODE, 0) == 25);
            check(evaluation.contributions().getOrDefault(Check.SURFACE_KELP, 0)
                + evaluation.contributions().getOrDefault(Check.MATURE_KELP_AGE, 0) == 20);
            EnumSet<Check> enabled = allChecks(); enabled.remove(Check.OPEN_SHAFTS);
            check(scored(fitted(0, 0, 7), evidence, enabled).score() == 130);
            check(scored(fitted(0, 0, 7), evidence, Set.of(Check.OPEN_SHAFTS)).score() == 0);
            Evaluation boundary = scored(fitted(0, 0, 7), List.of(finding(Check.BURIED_ACCESS_PATH, 0, 0)), allChecks());
            check(boundary.qualifies(35) && !boundary.qualifies(40));
        });
        run("negative-coordinate scoring and extreme distant coordinates are safe", () -> {
            check(scored(fitted(-9, -7, 7), List.of(finding(Check.BURIED_ACCESS_PATH, -9, -7),
                finding(Check.OPEN_SHAFTS, -12, -7)), allChecks()).score() == 15);
            check(scored(fitted(Integer.MIN_VALUE, Integer.MIN_VALUE, 7),
                List.of(finding(Check.BURIED_ACCESS_PATH, Integer.MAX_VALUE, Integer.MAX_VALUE)), allChecks()).score() == 0);
        });
        run("chunk-indexed scoring preserves all findings caps and shaft ranges with a single input traversal", () -> {
            List<Finding> data = new ArrayList<>();
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                for (Check check : Check.values()) data.add(finding(check, x, z));
            data.addAll(List.copyOf(data)); // Repeated overlapping windows must not multiply work or findings.
            data.add(finding(Check.BURIED_ACCESS_PATH, Integer.MAX_VALUE, Integer.MIN_VALUE));
            List<Candidate> candidates = List.of(new Candidate(0, 0), new Candidate(-2, 1),
                new Candidate(3, -4), new Candidate(Integer.MIN_VALUE, Integer.MAX_VALUE));
            for (int radius : new int[]{2, 7, 12}) {
                EnumSet<Check> enabled = allChecks(); enabled.remove(Check.MISMATCHED_SOIL);
                CountingFindings input = new CountingFindings(data);
                var actual = SuspiciousActivity.score(new Result(Status.ESTIMATED, candidates, radius, 4, 4), input, enabled);
                check(input.visited == data.size());
                for (int i = 0; i < candidates.size(); i++) {
                    Candidate candidate = candidates.get(i);
                    List<Finding> local = new ArrayList<>();
                    for (Finding finding : data) {
                        int reach = finding.check() == Check.OPEN_SHAFTS ? 3 : radius;
                        long dx = (long) finding.chunkX() - candidate.chunkX();
                        long dz = (long) finding.chunkZ() - candidate.chunkZ();
                        if (Math.abs(dx) <= reach && Math.abs(dz) <= reach && dx * dx + dz * dz <= reach * reach)
                            local.add(finding);
                    }
                    Evaluation expected = SuspiciousAnalysis.score(local, enabled), got = actual.get(i).evaluation();
                    check(got.score() == expected.score());
                    check(got.contributions().equals(expected.contributions()));
                    check(new HashSet<>(got.findings()).equals(new HashSet<>(expected.findings())));
                    check(got.findings().size() == expected.findings().size());
                }
            }
        });
        System.out.println("RESULT " + cases + " passed; " + assertions + " assertions; production activity estimator, no in-game proof");
    }
    private static List<GrowthObservation> compass(int centerX, int centerZ, int radius) {
        int distance = radius * 16;
        // Actual block centers straddle the geometric center by half a block.
        return List.of(point(centerX + distance - 1, centerZ - 1), point(centerX - distance, centerZ - 1),
            point(centerX - 1, centerZ + distance - 1), point(centerX - 1, centerZ - distance));
    }
    private static GrowthObservation point(int x, int z) {
        return new GrowthObservation(Check.MATURE_KELP_AGE, (long) x << 32 | z & 0xffffffffL, x, 40, z);
    }
    private static Result fitted(int chunkX, int chunkZ, int radius) {
        return new Result(Status.ESTIMATED, List.of(new Candidate(chunkX, chunkZ)), radius, 4, 1);
    }
    private static Finding finding(Check check, int chunkX, int chunkZ) {
        return new Finding(check, (long) chunkX << 32 | chunkZ & 0xffffffffL, chunkX, chunkZ);
    }
    private static final class CountingFindings extends java.util.AbstractCollection<Finding> {
        private final List<Finding> findings;
        int visited;
        CountingFindings(List<Finding> findings) { this.findings = findings; }
        @Override public int size() { return findings.size(); }
        @Override public java.util.Iterator<Finding> iterator() {
            var source = findings.iterator();
            return new java.util.Iterator<>() {
                @Override public boolean hasNext() { return source.hasNext(); }
                @Override public Finding next() { visited++; return source.next(); }
            };
        }
    }
    /** Small independent exhaustive oracle for fixtures whose best locations contain chunk centers. */
    private static void assertBestCenters(List<GrowthObservation> evidence, int radius, Result actual) {
        var unique = new java.util.TreeMap<Long, GrowthObservation>();
        for (var p : evidence) unique.merge((long) Math.floorDiv(p.blockX(), 16) << 32
            | Math.floorDiv(p.blockZ(), 16) & 0xffffffffL, p, SuspiciousActivity::representative);
        Set<Candidate> search = new HashSet<>();
        for (var p : unique.values()) for (int x = Math.floorDiv(p.blockX(), 16) - radius - 1;
             x <= Math.floorDiv(p.blockX(), 16) + radius + 1; x++)
            for (int z = Math.floorDiv(p.blockZ(), 16) - radius - 1;
                 z <= Math.floorDiv(p.blockZ(), 16) + radius + 1; z++) search.add(new Candidate(x, z));
        int maximum = 0;
        Set<Candidate> expected = new HashSet<>();
        for (var c : search) {
            int support = 0;
            for (var p : unique.values()) {
                double dx = c.chunkX() * 16.0 + 8 - (p.blockX() + .5);
                double dz = c.chunkZ() * 16.0 + 8 - (p.blockZ() + .5);
                if (dx * dx + dz * dz <= radius * radius * 256.0 + 1e-7) support++;
            }
            if (support > maximum) { maximum = support; expected.clear(); }
            if (support == maximum && support > 0) expected.add(c);
        }
        check(maximum > 0);
        check(new HashSet<>(actual.candidates()).equals(expected));
        check(actual.candidates().size() == expected.size());
    }
    private static EnumSet<Check> allChecks() { return EnumSet.allOf(Check.class); }
    private static Evaluation scored(Result fit, List<Finding> evidence, Set<Check> enabled) {
        return SuspiciousActivity.score(fit, evidence, enabled).getFirst().evaluation();
    }
    private static void run(String name, Runnable action) {
        action.run(); cases++; System.out.println("PASS " + name);
    }
    private static void check(boolean result) {
        assertions++; if (!result) throw new AssertionError("Assertion " + assertions);
    }
    private static void throwsType(Class<? extends Throwable> type, Runnable action) {
        assertions++;
        try { action.run(); } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}
