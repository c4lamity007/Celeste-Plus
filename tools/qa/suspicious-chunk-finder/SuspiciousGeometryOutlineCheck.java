package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;

/** Focused production-geometry fixture; does not establish actual in-game rendering. */
public final class SuspiciousGeometryOutlineCheck {
    private static int cases, assertions;

    public static void main(String[] args) {
        run("qualifying cells, immutable and bounded", () -> {
            Scene scene = new Scene(); Set<Pos> expected = scene.line(8,30,8,8,41,8);
            var job = finish(job(scene));
            check(new HashSet<>(job.buriedBlocks()).equals(expected), "exact filler cells");
            check(job.buriedBlocks().size() <= SuspiciousGeometry.FILLED_VISIT_LIMIT, "bounded");
            boolean refused = false;
            try { job.buriedBlocks().add(new Pos(0,0,0)); }
            catch (UnsupportedOperationException expectedFailure) { refused = true; }
            check(refused, "immutable returned list");
        });
        run("failed seeds excluded and duplicate summaries deduplicated", () -> {
            Scene scene = new Scene(); scene.line(4,55,4,4,57,4);
            Set<Pos> expected = scene.line(8,30,8,8,41,8);
            List<ChunkSummary> summaries = summaries(scene); summaries.addAll(List.copyOf(summaries));
            var job = finish(new SuspiciousGeometry.Job(scene,0,0,summaries));
            check(new HashSet<>(job.buriedBlocks()).equals(expected), "failed seeds never appear");
            check(job.buriedBlocks().size() == expected.size(), "one cell per position");
        });
        run("route crossing chunk border retains actual coordinates", () -> {
            Scene scene = new Scene(); Set<Pos> expected = scene.line(12,30,8,21,30,8);
            check(new HashSet<>(finish(job(scene)).buriedBlocks()).equals(expected), "both chunk sides");
        });
        run("incomplete qualified job keeps positions private until completion", () -> {
            Scene scene = new Scene(); scene.line(8,30,8,8,41,8);
            var job = job(scene);
            advanceUntilBuriedFinding(job);
            check(!job.isDone(), "qualification precedes terminal job step");
            check(job.buriedBlocks().isEmpty(), "no partial publication");
            finish(job); check(job.buriedBlocks().size() == 12, "available on completion");
        });
        run("cancelled job cannot publish completed or partial route", () -> {
            Scene scene = new Scene(); scene.line(8,30,8,8,41,8);
            var job = job(scene); advanceUntilBuriedFinding(job);
            boolean cancelled = false; Thread.currentThread().interrupt();
            try { job.advance(1,Long.MAX_VALUE); }
            catch (CancellationException expected) { cancelled = true; }
            finally { Thread.interrupted(); }
            check(cancelled, "cancellation propagated");
            check(job.buriedBlocks().isEmpty(), "cancelled positions cleared");
            check(job.findings().isEmpty(), "cancelled findings cleared");
        });
        run("oversized component cannot reappear as qualifying unvisited tail", () -> {
            Scene scene = new Scene();
            for (int x=8;x<=9;x++) for (int z=8;z<=9;z++) scene.line(x,20,z,x,319,z);
            var job = finish(job(scene));
            check(job.buriedBlocks().isEmpty(), "exhausted component has no outline");
            check(job.findings().stream().noneMatch(f -> f.check() == Check.BURIED_ACCESS_PATH),
                    "exhausted component has no score");
        });
        run("natural broad patch and empty terrain have no outlines", () -> {
            Scene scene = new Scene();
            for (int x=5;x<=11;x++) for (int z=5;z<=11;z++) scene.line(x,30,z,x,36,z);
            check(finish(job(scene)).buriedBlocks().isEmpty(), "broad deposit rejected");
            check(finish(job(new Scene())).buriedBlocks().isEmpty(), "no candidates");
        });
        run("repeat completed job is stable and new scene is independent", () -> {
            Scene scene = new Scene(); scene.line(8,30,8,8,41,8);
            var job = finish(job(scene)); List<Pos> first = job.buriedBlocks();
            check(job.advance(512,Long.MAX_VALUE)==0, "completed job does no work");
            check(job.buriedBlocks().equals(first), "stable repeated reads");
            check(finish(job(new Scene())).buriedBlocks().isEmpty(), "new lifecycle has no old route");
        });
        System.out.println("RESULT " + cases + " passed; " + assertions + " assertions; fixture only");
    }

    private static void advanceUntilBuriedFinding(SuspiciousGeometry.Job job) {
        int steps=0;
        while (!job.isDone() && job.findings().stream().noneMatch(f -> f.check()==Check.BURIED_ACCESS_PATH)) {
            check(job.advance(1,Long.MAX_VALUE)<=1,"one work step");
            if (++steps>100_000) throw new AssertionError("unbounded job");
        }
        check(job.findings().stream().anyMatch(f -> f.check()==Check.BURIED_ACCESS_PATH),"found route");
    }
    private static SuspiciousGeometry.Job finish(SuspiciousGeometry.Job job) {
        int calls=0;
        while (!job.isDone()) {
            check(job.advance(512,Long.MAX_VALUE)<=512,"shared work budget");
            if (++calls>1000) throw new AssertionError("unbounded job");
        }
        return job;
    }
    private static SuspiciousGeometry.Job job(Scene scene) {
        return new SuspiciousGeometry.Job(scene,0,0,summaries(scene));
    }
    private static List<ChunkSummary> summaries(Scene scene) {
        Map<Long,List<Pos>> grouped = new HashMap<>();
        for (Pos p:scene.cells.keySet()) grouped.computeIfAbsent(chunkId(p.x()>>4,p.z()>>4),i->new ArrayList<>()).add(p);
        List<ChunkSummary> result=new ArrayList<>();
        for (var entry:grouped.entrySet()) {
            long id=entry.getKey(); result.add(new ChunkSummary((int)(id>>32),(int)id,entry.getValue(),true));
        }
        return result;
    }
    private static final class Scene implements Terrain {
        final Map<Pos,Integer> cells=new HashMap<>();
        Set<Pos> line(int x1,int y1,int z1,int x2,int y2,int z2) {
            Set<Pos> added=new HashSet<>();
            for(int x=x1;x<=x2;x++) for(int y=y1;y<=y2;y++) for(int z=z1;z<=z2;z++) {
                Pos p=new Pos(x,y,z);cells.put(p,COBBLE);added.add(p);
            }
            return added;
        }
        public int cell(int x,int y,int z) {
            if(x<-16||x>=32||z<-16||z>=32||y<floorY()||y>=topY())return UNKNOWN;
            return cells.getOrDefault(new Pos(x,y,z),y<=350?STONE:AIR);
        }
        public int floorY(){return 0;}
        public int topY(){return 384;}
        public int surfaceY(int x,int z){return x<-16||x>=32||z<-16||z>=32?Integer.MIN_VALUE:350;}
    }
    private static void run(String name,Runnable test) {test.run();cases++;System.out.println("PASS "+name);}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
}
