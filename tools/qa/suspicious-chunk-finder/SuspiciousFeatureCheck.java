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
        run("actual worker publishes local land evidence without a growth estimate or old scoring center", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                publishEvidence(feature, List.of(new Finding(Check.STRIPPED_GEODE, 77, 2, -1)));
                check(evidence(feature).isEmpty());
                completeActivity(feature);
                var estimate = (SuspiciousActivity.Result) field(feature, "activityResult");
                check(estimate.status() == SuspiciousActivity.Status.INSUFFICIENT && estimate.candidates().isEmpty());
                var scores = scores(feature);
                check(scores.size() == 1);
                check(scores.getFirst().candidate().equals(new SuspiciousActivity.Candidate(2, -1)));
                check(scores.getFirst().origin() == SuspiciousActivity.Origin.LOCAL_EVIDENCE);
                check(scores.getFirst().evaluation().score() == 65);
                SuspiciousSnapshots.Cache cache = (SuspiciousSnapshots.Cache) field(feature, "cache");
                cache.put(chunk(2, -1, STONE, 1));
                invoke(feature, "flushActivityMarkers");
                var markers = (List<?>) field(feature, "displayedActivity");
                check(markers.size() == 1);
                Object marker = markers.getFirst();
                check(recordValue(marker, "x").equals(2) && recordValue(marker, "z").equals(-1));
                check(recordValue(marker, "origin") == SuspiciousActivity.Origin.LOCAL_EVIDENCE);
                check(Math.abs((double) recordValue(marker, "y") - 16.025) < 0.00001);
                @SuppressWarnings("unchecked") Set<Long> dirty = (Set<Long>) field(feature, "dirty");
                dirty.add(key(2, -1));
                invoke(feature, "flushActivityMarkers");
                check(Double.isNaN((double) recordValue(((List<?>) field(feature, "displayedActivity")).getFirst(), "y")));
                field(feature, "showActivity", false);
                invoke(feature, "flushActivityMarkers");
                check(((List<?>) field(feature, "displayedActivity")).isEmpty());
            } finally { invoke(feature, "reset"); }
        });
        run("disabled land checks do not publish candidates through the actual worker", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                publishEvidence(feature, List.of(new Finding(Check.STRIPPED_GEODE, 88, 0, 0)));
                field(feature, "checks", EnumSet.of(Check.SURFACE_KELP));
                completeActivity(feature);
                check(scores(feature).isEmpty());
                invoke(feature, "flushActivityMarkers");
                check(((List<?>) field(feature, "displayedActivity")).isEmpty());
            } finally { invoke(feature, "reset"); }
        });
        run("missing and stripped geode settings preserve separate labels and appended ordinal", () -> {
            var labelsField = SuspiciousChunkFinderFeature.class.getDeclaredField("LABELS"); labelsField.setAccessible(true);
            String[] labels = (String[]) labelsField.get(null);
            check(labels.length == Check.values().length);
            check(Check.MISSING_BUDDING_AMETHYST.ordinal() == 4 && labels[4].equals("Missing budding"));
            check(Check.OPEN_SHAFTS.ordinal() == 8 && labels[8].equals("Open shafts"));
            check(Check.STRIPPED_GEODE.ordinal() == 9 && labels[9].equals("Stripped geodes"));
        });
        run("missing budding remains a25-point local reason below the default flag score", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                publishEvidence(feature, List.of(new Finding(Check.MISSING_BUDDING_AMETHYST, 89, 0, 0)));
                completeActivity(feature);
                check(evidence(feature).isEmpty());
                check(scores(feature).size() == 1);
                var score = scores(feature).getFirst().evaluation();
                check(score.score() == 25 && !score.qualifies((int) field(feature, "threshold")));
                check(score.contributions().equals(Map.of(Check.MISSING_BUDDING_AMETHYST, 25)));
            } finally { invoke(feature, "reset"); }
        });
        run("actual worker keeps missing budding and stripped geode toggles independent", () -> {
            for (Check selected : List.of(Check.MISSING_BUDDING_AMETHYST, Check.STRIPPED_GEODE)) {
                var feature = new SuspiciousChunkFinderFeature();
                try {
                    publishEvidence(feature, List.of(new Finding(Check.MISSING_BUDDING_AMETHYST, 90, 0, 0),
                        new Finding(Check.STRIPPED_GEODE, 91, 0, 0)));
                    field(feature, "checks", EnumSet.of(selected));
                    completeActivity(feature);
                    check(scores(feature).size() == 1 && evidence(feature).isEmpty());
                    var score = scores(feature).getFirst().evaluation();
                    check(score.score() == selected.weight());
                    check(score.contributions().equals(Map.of(selected, selected.weight())));
                } finally { invoke(feature, "reset"); }
            }
        });
        run("continuous movement and added observations coalesce without canceling useful work", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            var started = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task); thread.setDaemon(true); return thread;
            });
            try {
                executor.submit(() -> { started.countDown(); release.await(); return null; });
                check(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                field(feature, "worker", executor); field(feature, "tick", 100);
                publishEvidence(feature, List.of(new Finding(Check.STRIPPED_GEODE, 101, 0, 0)));
                invoke(feature, "launchActivity");
                var running = (java.util.concurrent.Future<?>) field(feature, "activityFuture");
                long version = (long) field(feature, "activityVersion");
                int deadline = (int) field(feature, "nextEstimate");
                for (int x = 1; x <= 4; x++) {
                    field(feature, "tick", 100 + x);
                    call(feature, "moveActivityCenter", new Class<?>[]{int.class, int.class}, x, 0);
                    remember(feature, population(x, 0, 0)); invoke(feature, "publishNow");
                    check(field(feature, "activityFuture") == running && !running.isCancelled());
                    check((long) field(feature, "activityVersion") == version);
                    check((int) field(feature, "nextEstimate") == deadline && (boolean) field(feature, "activityDirty"));
                }
                release.countDown(); running.get(5, java.util.concurrent.TimeUnit.SECONDS);
                call(feature, "moveActivityCenter", new Class<?>[]{int.class, int.class}, 5, 0);
                check(field(feature, "activityFuture") == null && scores(feature).size() == 1);
                check(scores(feature).getFirst().evaluation().score() == 65);
                ((SuspiciousSnapshots.Cache) field(feature, "cache")).put(chunk(STONE, 1));
                invoke(feature, "flushActivityMarkers"); check(((List<?>) field(feature, "displayedActivity")).size() == 1);
                field(feature, "tick", deadline); invoke(feature, "launchAnalysis");
                var replacement = (java.util.concurrent.Future<?>) field(feature, "activityFuture");
                check(replacement != null && replacement != running);
                replacement.get(5, java.util.concurrent.TimeUnit.SECONDS); invoke(feature, "collectActivity");
                check(((SuspiciousActivity.Result) field(feature, "activityResult")).evidenceChunks() == 4);
                check(!(boolean) field(feature, "activityDirty"));
            } finally { release.countDown(); invoke(feature, "reset"); executor.shutdownNow(); }
        });
        run("removed evidence and new shaft penalties revoke even an already completed stale job", () -> {
            for (boolean newShaft : List.of(false, true)) {
                var feature = new SuspiciousChunkFinderFeature();
                try {
                    var geode = new Finding(Check.STRIPPED_GEODE, 102, 0, 0);
                    publishEvidence(feature, List.of(geode)); completeActivity(feature);
                    check(scores(feature).getFirst().evaluation().score() == 65);
                    field(feature, "tick", 50); invoke(feature, "launchActivity");
                    var completed = (java.util.concurrent.Future<?>) field(feature, "activityFuture");
                    completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    long version = (long) field(feature, "activityVersion");
                    publishEvidence(feature, newShaft ? List.of(geode, new Finding(Check.OPEN_SHAFTS, 103, 3, 0)) : List.of());
                    check(field(feature, "activityFuture") == null && scores(feature).isEmpty());
                    check((long) field(feature, "activityVersion") > version && (int) field(feature, "nextEstimate") <= 50);
                    field(feature, "activityFuture", completed); invoke(feature, "collectActivity");
                    check(scores(feature).isEmpty() && field(feature, "activityResult") == null);
                    completeActivity(feature);
                    check(newShaft ? scores(feature).getFirst().evaluation().score() == 45 : scores(feature).isEmpty());
                } finally { invoke(feature, "reset"); }
            }
        });
        run("movement clips previous candidates outside the new bounded local region", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                publishEvidence(feature, List.of(new Finding(Check.STRIPPED_GEODE, 104, 0, 0)));
                completeActivity(feature); check(scores(feature).size() == 1);
                call(feature, "moveActivityCenter", new Class<?>[]{int.class, int.class}, 1000, -1000);
                invoke(feature, "flushActivityMarkers");
                check(scores(feature).isEmpty() && ((List<?>) field(feature, "displayedActivity")).isEmpty());
                check((boolean) field(feature, "activityDirty"));
                invoke(feature, "launchActivity");
                ((java.util.concurrent.Future<?>) field(feature, "activityFuture")).get(5, java.util.concurrent.TimeUnit.SECONDS);
                invoke(feature, "collectActivity");
                check(scores(feature).isEmpty());
            } finally { invoke(feature, "reset"); }
        });
        run("stale pending centers drain with a strict per-tick bound and unload removes queued IDs", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                field(feature, "showActivity", false);
                @SuppressWarnings("unchecked") Set<Long> pending = (Set<Long>) field(feature, "pending");
                for (int x = 0; x < 65; x++) pending.add(key(x, 0));
                invoke(feature, "launchAnalysis"); check(pending.size() == 33 && field(feature, "future") == null);
                invoke(feature, "launchAnalysis"); check(pending.size() == 1);
                invoke(feature, "launchAnalysis"); check(pending.isEmpty());
                pending.add(key(100, 100));
                call(feature, "discardChunk", new Class<?>[]{int.class, int.class}, 100, 100);
                check(pending.isEmpty());
            } finally { invoke(feature, "reset"); }
        });
        run("new neighbor capture preserves completed observations while queued completion remains exact", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                var cache = (SuspiciousSnapshots.Cache) field(feature, "cache"); cache.put(chunk(STONE, 1));
                Object observation = observation(Map.of(key(0, 0), 1L, key(1, 0), -1L));
                @SuppressWarnings("unchecked") Map<Long, Object> results = (Map<Long, Object>) field(feature, "results");
                results.put(key(0, 0), observation); invoke(feature, "publishNow"); invoke(feature, "launchActivity");
                var running = field(feature, "activityFuture"); long version = (long) field(feature, "activityVersion");
                call(feature, "acceptCapture", new Class<?>[]{SuspiciousSnapshots.Chunk.class}, chunk(1, 0, STONE, 2));
                invoke(feature, "publishNow");
                check(results.get(key(0, 0)) == observation && field(feature, "activityFuture") == running);
                check((long) field(feature, "activityVersion") == version);
                @SuppressWarnings("unchecked") Set<Long> pending = (Set<Long>) field(feature, "pending");
                check(pending.contains(key(0, 0)) && pending.contains(key(1, 0)));
                ((java.util.concurrent.Future<?>) running).get(5, java.util.concurrent.TimeUnit.SECONDS);
                invoke(feature, "collectActivity"); check(scores(feature).getFirst().evaluation().score() == 65);
                results.clear(); field(feature, "future", java.util.concurrent.CompletableFuture.completedFuture(observation));
                invoke(feature, "collectResult"); check(results.isEmpty() && pending.contains(key(0, 0)));
            } finally { invoke(feature, "reset"); }
        });
        run("first chunk availability is enrichment while known chunk change still revokes", () -> {
            var feature = new SuspiciousChunkFinderFeature();
            try {
                ((SuspiciousSnapshots.Cache) field(feature, "cache")).put(chunk(STONE, 1));
                @SuppressWarnings("unchecked") Map<Long, Object> results = (Map<Long, Object>) field(feature, "results");
                Object observation = observation(Map.of(key(0, 0), 1L, key(1, 0), -1L));
                results.put(key(0, 0), observation); invoke(feature, "publishNow"); completeActivity(feature);
                long version = (long) field(feature, "activityVersion");
                @SuppressWarnings("unchecked") Set<Long> changed = (Set<Long>) field(feature, "changedChunks");
                @SuppressWarnings("unchecked") Set<Long> dirty = (Set<Long>) field(feature, "dirty");
                changed.add(key(1, 0)); invoke(feature, "collectChanges"); invoke(feature, "publishNow");
                check(results.get(key(0, 0)) == observation && scores(feature).getFirst().evaluation().score() == 65);
                check(changed.isEmpty() && !dirty.contains(key(1, 0)) && (long) field(feature, "activityVersion") == version);
                changed.add(key(0, 0)); invoke(feature, "collectChanges"); invoke(feature, "publishNow");
                check(results.isEmpty() && scores(feature).isEmpty() && dirty.contains(key(0, 0)));
                check((long) field(feature, "activityVersion") > version);
            } finally { invoke(feature, "reset"); }
        });
        run("capture enrichment never preserves changed dirty or lost known dependencies", () -> {
            for (String mutation : List.of("revision", "dirty", "lost")) {
                var feature = new SuspiciousChunkFinderFeature();
                try {
                    var cache = (SuspiciousSnapshots.Cache) field(feature, "cache"); cache.put(chunk(STONE, 1));
                    @SuppressWarnings("unchecked") Map<Long, Object> results = (Map<Long, Object>) field(feature, "results");
                    results.put(key(0, 0), observation(Map.of(key(0, 0), 1L, key(1, 0), -1L)));
                    invoke(feature, "publishNow"); completeActivity(feature); check(!scores(feature).isEmpty());
                    if (mutation.equals("revision")) cache.put(chunk(DIRT, 3));
                    else if (mutation.equals("lost")) cache.unload(key(0, 0));
                    else { @SuppressWarnings("unchecked") Set<Long> dirty = (Set<Long>) field(feature, "dirty"); dirty.add(key(0, 0)); }
                    call(feature, "acceptCapture", new Class<?>[]{SuspiciousSnapshots.Chunk.class}, chunk(1, 0, STONE, 2));
                    invoke(feature, "publishNow");
                    check(results.isEmpty() && scores(feature).isEmpty() && field(feature, "activityResult") == null);
                } finally { invoke(feature, "reset"); }
            }
        });
        run("stale local evidence results are rejected after version or session changes", () -> {
            for (String invalidated : List.of("activityVersion", "generation")) {
                var feature = new SuspiciousChunkFinderFeature();
                try {
                    publishEvidence(feature, List.of(new Finding(Check.ROTATED_DEEPSLATE, 99, 0, 0)));
                    invoke(feature, "launchActivity");
                    ((java.util.concurrent.Future<?>) field(feature, "activityFuture")).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    field(feature, invalidated, (long) field(feature, invalidated) + 1L);
                    invoke(feature, "collectActivity");
                    check(scores(feature).isEmpty());
                    check(field(feature, "activityResult") == null && field(feature, "activityFuture") == null);
                } finally { invoke(feature, "reset"); }
            }
        });
        System.out.println("RESULT " + cases + " passed; " + assertions + " assertions; actual Feature reflection, no game/auth initialized");
    }
    private static Object call(Object feature, String name, Class<?>[] types, Object... values) throws Exception {
        var method = SuspiciousChunkFinderFeature.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(feature, values);
    }
    private static Object observation(Map<Long, Long> inputs) throws Exception {
        var type = Class.forName(SuspiciousChunkFinderFeature.class.getName() + "$Result");
        var constructor = type.getDeclaredConstructor(long.class, long.class, Map.class, Evaluation.class, List.class);
        constructor.setAccessible(true);
        var evaluation = SuspiciousAnalysis.score(List.of(new Finding(Check.STRIPPED_GEODE, 105, 0, 0)), EnumSet.allOf(Check.class));
        return constructor.newInstance(key(0, 0), 0L, inputs, evaluation, List.of());
    }
    private static void publishEvidence(Object feature, List<Finding> findings) throws Exception {
        Evaluation evaluation = SuspiciousAnalysis.score(findings, EnumSet.allOf(Check.class));
        Class<?> result = Class.forName(SuspiciousChunkFinderFeature.class.getName() + "$Result");
        var constructor = result.getDeclaredConstructor(long.class, long.class, Map.class, Evaluation.class, List.class);
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked") Map<Long, Object> results = (Map<Long, Object>) field(feature, "results");
        // Deliberately distant scoring-window ID: only the actual finding location may become a marker.
        results.put(key(31, 31), constructor.newInstance(key(31, 31), 0L, Map.of(), evaluation, List.of()));
        invoke(feature, "publishNow");
    }
    private static void completeActivity(Object feature) throws Exception {
        invoke(feature, "launchActivity");
        ((java.util.concurrent.Future<?>) field(feature, "activityFuture")).get(5, java.util.concurrent.TimeUnit.SECONDS);
        invoke(feature, "collectActivity");
    }
    @SuppressWarnings("unchecked") private static List<SuspiciousActivity.ScoredCandidate> scores(Object feature) throws Exception {
        return (List<SuspiciousActivity.ScoredCandidate>) field(feature, "activityScores");
    }
    private static Object recordValue(Object value, String name) throws Exception {
        var accessor = value.getClass().getDeclaredMethod(name); accessor.setAccessible(true); return accessor.invoke(value);
    }
    private static SuspiciousSnapshots.Chunk chunk(int cell, long revision) {
        return chunk(0, 0, cell, revision);
    }
    private static SuspiciousSnapshots.Chunk chunk(int x, int z, int cell, long revision) {
        int[] surfaces = new int[256]; Arrays.fill(surfaces, 15);
        return new SuspiciousSnapshots.Chunk(x, z, 0, 16, new short[][]{{(short) cell}}, surfaces, revision, revision);
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
