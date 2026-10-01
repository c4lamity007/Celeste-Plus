package dev.c4lam.celeste.module.impl.visual.suspicious;

import dev.c4lam.celeste.module.impl.visual.SuspiciousChunkFinderFeature;
import java.lang.reflect.*;
import java.util.*;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousSnapshots.key;

/** Calls the actual Feature's history methods; no game, auth, renderer or substitute Feature is started. */
public final class SuspiciousFeatureCheck {
    private static int cases, assertions;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass the actual runtime classes directory");
        run("fixture loads the five actual runtime classes", () -> {
            var expected = java.nio.file.Path.of(args[0]).toRealPath();
            for (Class<?> type : List.of(SuspiciousChunkFinderFeature.class, SuspiciousActivity.class,
                SuspiciousAnalysis.class, SuspiciousSnapshots.class, SuspiciousGeometry.class))
                check(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toRealPath().equals(expected));
        });
        run("qualified nearby history can exceed512 chunks without disabling estimates", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            remember(feature, population(0, 0, 17));
            check(evidence(feature).size() == 33 * 33);
            check(kinds(feature).size() == 33 * 33);
            check(evidence(feature).containsKey(key(-16, 16)));
            check(!evidence(feature).containsKey(key(-17, 0)));
            check(!evidence(feature).containsKey(key(0, 17)));
            var local = evidence(feature).values().stream().filter(p -> Math.abs(p.blockX() >> 4) <= 14
                && Math.abs(p.blockZ() >> 4) <= 14).toList();
            check(local.size() == 29 * 29);
            check(SuspiciousActivity.estimate(local, 7).status() == SuspiciousActivity.Status.ESTIMATED);
        });
        run("movement prunes only beyond twice the radius plus two chunks", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            remember(feature, population(0, 0, 16));
            GrowthObservation preserved = evidence(feature).get(key(-15, -16));
            field(feature, "activityX", 1);
            invoke(feature, "pruneActivityHistory");
            check(evidence(feature).size() == 32 * 33);
            check(evidence(feature).get(key(-15, -16)) == preserved);
            check(!evidence(feature).containsKey(key(-16, 0)));
            check(kinds(feature).keySet().equals(evidence(feature).keySet()));
            remember(feature, population(17, 0, 0));
            check(evidence(feature).containsKey(key(17, 0)));
        });
        run("radius changes preserve the exact local retention bounds", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            field(feature, "activityRadius", 12);
            remember(feature, population(0, 0, 27));
            check(evidence(feature).size() == 53 * 53);
            field(feature, "activityRadius", 2);
            invoke(feature, "pruneActivityHistory");
            check(evidence(feature).size() == 13 * 13);
            check(evidence(feature).containsKey(key(-6, 6)));
            check(!evidence(feature).containsKey(key(7, 0)));
            check(kinds(feature).keySet().equals(evidence(feature).keySet()));
        });
        run("a long flight accepts new local evidence after the old area is pruned", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            remember(feature, population(0, 0, 16));
            field(feature, "activityX", 1000); field(feature, "activityZ", -1000);
            invoke(feature, "pruneActivityHistory");
            check(evidence(feature).isEmpty() && kinds(feature).isEmpty());
            remember(feature, population(1000, -1000, 16));
            check(evidence(feature).size() == 33 * 33);
            check(evidence(feature).containsKey(key(1000, -1000)));
            check(!evidence(feature).containsKey(key(0, 0)));
            var local = evidence(feature).values().stream().filter(p -> Math.abs((p.blockX() >> 4) - 1000) <= 14
                && Math.abs((p.blockZ() >> 4) + 1000) <= 14).toList();
            check(SuspiciousActivity.estimate(local, 7).status() == SuspiciousActivity.Status.ESTIMATED);
        });
        run("population reasons survive positional deduplication and combine with real geometry findings", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            GrowthObservation sample = sample(0, 0);
            Evaluation growth = new Evaluation(20, Map.of(), List.of(), List.of(sample),
                Map.of(key(0, 0), Set.of(Check.SURFACE_KELP, Check.MATURE_KELP_AGE)));
            remember(feature, growth); remember(feature, growth);
            check(evidence(feature).size() == 1 && kinds(feature).get(key(0, 0)).size() == 2);
            List<Finding> geometry = List.of(new Finding(Check.BURIED_ACCESS_PATH, 1, 0, 0),
                new Finding(Check.OPEN_SHAFTS, 2, 3, 0));
            Evaluation evaluated = SuspiciousAnalysis.score(geometry, EnumSet.allOf(Check.class));
            Class<?> result = Class.forName(SuspiciousChunkFinderFeature.class.getName() + "$Result");
            var constructor = result.getDeclaredConstructor(long.class, long.class, Map.class, Evaluation.class, List.class);
            constructor.setAccessible(true);
            field(feature, "displayed", List.of(constructor.newInstance(0L, 0L, Map.of(), evaluated, List.of())));
            @SuppressWarnings("unchecked") Set<Finding> findings = (Set<Finding>) invoke(feature, "activityFindings");
            check(findings.containsAll(geometry));
            check(findings.stream().filter(f -> f.check() == Check.SURFACE_KELP || f.check() == Check.MATURE_KELP_AGE).count() == 2);
            var fit = new SuspiciousActivity.Result(SuspiciousActivity.Status.ESTIMATED,
                List.of(new SuspiciousActivity.Candidate(0, 0)), 7, 1, 1);
            var score = SuspiciousActivity.score(fit, findings, EnumSet.allOf(Check.class)).getFirst().evaluation();
            check(score.score() == 35 && score.contributions().get(Check.OPEN_SHAFTS) == -20);
        });
        run("history pruning does not replace first terrain observations with later terrain", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            SuspiciousSnapshots.Cache cache = (SuspiciousSnapshots.Cache) field(feature, "cache");
            var first = chunk(STONE, 1); var later = chunk(DIRT, 2);
            cache.put(first); cache.put(later);
            remember(feature, population(0, 0, 16));
            field(feature, "activityX", 1000); invoke(feature, "pruneActivityHistory");
            check(cache.first(key(0, 0)) == first);
            check(cache.first(key(0, 0)).cell(0, 1, 0) == STONE);
            check(cache.live(key(0, 0)) == later);
        });
        run("suspend retains baseline history while reset clears it", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            SuspiciousSnapshots.Cache cache = (SuspiciousSnapshots.Cache) field(feature, "cache");
            var first = chunk(STONE, 1); cache.put(first);
            remember(feature, population(0, 0, 1));
            invoke(feature, "suspend");
            check(evidence(feature).size() == 9 && kinds(feature).size() == 9);
            check(cache.live(key(0, 0)) == null && cache.first(key(0, 0)) == first);
            invoke(feature, "reset");
            check(evidence(feature).isEmpty() && kinds(feature).isEmpty());
            check(cache.first(key(0, 0)) == null);
        });
        System.out.println("RESULT " + cases + " passed; " + assertions + " assertions; actual Feature reflection, no game/auth initialized");
    }
    private static SuspiciousSnapshots.Chunk chunk(int cell, long revision) {
        int[] surfaces = new int[256]; Arrays.fill(surfaces, 15);
        return new SuspiciousSnapshots.Chunk(0, 0, 0, 16, new short[][]{{(short) cell}}, surfaces, revision, revision);
    }
    private static Evaluation population(int cx, int cz, int radius) {
        List<GrowthObservation> samples = new ArrayList<>(); Map<Long, Set<Check>> kinds = new HashMap<>();
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
            samples.add(sample(x, z)); kinds.put(key(x, z), Set.of(Check.MATURE_KELP_AGE));
        }
        return new Evaluation(15, Map.of(), List.of(), samples, kinds);
    }
    private static GrowthObservation sample(int cx, int cz) {
        return new GrowthObservation(Check.MATURE_KELP_AGE, key(cx, cz), cx * 16 + 8, 40, cz * 16 + 8);
    }
    private static void remember(Object feature, Evaluation evaluation) throws Exception {
        var method = SuspiciousChunkFinderFeature.class.getDeclaredMethod("rememberGrowth", Evaluation.class);
        method.setAccessible(true); method.invoke(feature, evaluation);
    }
    @SuppressWarnings("unchecked") private static Map<Long, GrowthObservation> evidence(Object feature) throws Exception {
        return (Map<Long, GrowthObservation>) field(feature, "activityEvidence");
    }
    @SuppressWarnings("unchecked") private static Map<Long, Set<Check>> kinds(Object feature) throws Exception {
        return (Map<Long, Set<Check>>) field(feature, "growthKinds");
    }
    private static Object field(Object feature, String name) throws Exception {
        var field = SuspiciousChunkFinderFeature.class.getDeclaredField(name); field.setAccessible(true); return field.get(feature);
    }
    private static void field(Object feature, String name, Object value) throws Exception {
        var field = SuspiciousChunkFinderFeature.class.getDeclaredField(name); field.setAccessible(true); field.set(feature, value);
    }
    private static Object invoke(Object feature, String name) throws Exception {
        var method = SuspiciousChunkFinderFeature.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(feature);
    }
    @FunctionalInterface private interface Test { void run() throws Exception; }
    private static void run(String name, Test test) throws Exception { test.run(); cases++; System.out.println("PASS " + name); }
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Assertion " + assertions); }
}
