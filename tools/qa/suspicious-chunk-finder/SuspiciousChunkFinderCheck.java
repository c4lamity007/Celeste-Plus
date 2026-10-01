package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;

/** Exercises the actual pure detection core against explicit small terrain scenes. */
public final class SuspiciousChunkFinderCheck {
    private static int passed, failed, assertions;
    private static final Set<Check> ALL = EnumSet.allOf(Check.class);

    public static void main(String[] args) {
        scoring();
        rockAndSoil();
        plants();
        geodes();
        growthObservations();
        windows();
        geometry();
        snapshots();
        System.out.println("RESULT " + passed + " passed, " + failed + " failed; " + assertions
            + " assertions; production pure Java core, no in-game proof");
        if (failed != 0) throw new AssertionError(failed + " fixture cases failed");
    }

    private static void scoring() {
        run("weights apply once per window", () -> {
            int[] expected = {30,10,10,15,25,15,15,35,0};
            Check[] kinds = Check.values();
            eq(expected.length, kinds.length, "intentional nine-check contract");
            for (int i=0; i<kinds.length; i++) {
                List<Finding> repeated = new ArrayList<>();
                for (int n=0;n<100;n++) repeated.add(finding(kinds[i],n,n%3-1,n/3%3-1));
                eq(expected[i], score(repeated, ALL).score(), "single weight " + kinds[i]);
            }
        });
        run("geode cap 25 and kelp cap 20", () -> {
            eq(25, scored(Check.MATURE_GEODE,Check.MISSING_BUDDING_AMETHYST), "geode cap");
            eq(15, scored(Check.MATURE_GEODE), "mature geode remains independent");
            eq(20, scored(Check.SURFACE_KELP,Check.MATURE_KELP_AGE), "kelp cap");
            Evaluation e=score(List.of(finding(Check.SURFACE_KELP,1,0,0),finding(Check.MATURE_KELP_AGE,1,0,0)),ALL);
            eq(15,e.contributions().get(Check.SURFACE_KELP),"surface contribution");
            eq(5,e.contributions().get(Check.MATURE_KELP_AGE),"age residual contribution");
        });
        run("maximum score 130 and one shaft deduction 20", () -> {
            List<Finding> evidence = new ArrayList<>();
            for (Check check:Check.values()) if (check!=Check.OPEN_SHAFTS) evidence.add(finding(check,1,0,0));
            eq(130,score(evidence,ALL).score(),"maximum");
            for(int n=0;n<20;n++) evidence.add(finding(Check.OPEN_SHAFTS,n,0,0));
            eq(110,score(evidence,ALL).score(),"one deduction");
        });
        run("deduction floors at zero and toggles exclude evidence", () -> {
            List<Finding> evidence=List.of(finding(Check.GROUNDED_VINES,1,0,0),finding(Check.OPEN_SHAFTS,2,0,0));
            eq(0,score(evidence,ALL).score(),"zero floor");
            eq(10,score(evidence,EnumSet.of(Check.GROUNDED_VINES)).score(),"deduction disabled");
            eq(0,score(evidence,Set.of()).score(),"all checks disabled");
        });
        run("threshold is inclusive", () -> {
            Evaluation e=score(List.of(finding(Check.BURIED_ACCESS_PATH,1,0,0)),ALL);
            check(e.qualifies(35),"35 qualifies at 35");
            check(!e.qualifies(40),"35 does not qualify at 40");
        });
    }

    private static void rockAndSoil() {
        run("rotated deepslate in natural rock scores 30", () -> eq(30,evaluate(rotated(8,30,8)).score(),"horizontal axis"));
        run("ordinary vertical deepslate does not score", () -> {
            Scene s=rotated(8,30,8);s.set(8,30,8,DEEPSLATE);eq(0,evaluate(s).score(),"natural axis");
        });
        run("natural structure context excludes rotated deepslate", () -> {
            Scene s=rotated(8,30,8);s.set(9,30,8,STRUCTURE);eq(0,evaluate(s).score(),"structure nearby");
        });
        run("unknown local rock context cannot establish a rotation anomaly", () -> {
            Scene s=rotated(8,30,8);s.fill(6,28,6,10,32,10,UNKNOWN);s.set(8,30,8,HORIZONTAL_DEEPSLATE);
            eq(0,evaluate(s).score(),"unknown rock");
        });
        for(int surrounding:new int[]{COARSE_DIRT,ROOTED_DIRT,PODZOL,MYCELIUM}) {
            run("ordinary dirt patch in soil kind " + surrounding, () -> eq(10,evaluate(soil(surrounding)).score(),"mismatched soil"));
        }
        run("ordinary dirt landscape is not a mismatched patch", () -> eq(0,evaluate(soil(DIRT)).score(),"ordinary dirt"));
        run("isolated dirt sample is too small", () -> {
            Scene s=soil(COARSE_DIRT);s.fill(7,64,7,8,64,8,COARSE_DIRT);s.set(7,64,7,DIRT);
            eq(0,evaluate(s).score(),"one dirt");
        });
        run("large natural dirt seam is not a compact edit", () -> {
            Scene s=soil(COARSE_DIRT);s.fill(5,64,5,10,64,10,DIRT);eq(0,evaluate(s).score(),"large patch");
        });
    }

    private static void plants() {
        run("eight grounded vine ends score 10", () -> eq(10,evaluate(vines(8,8)).score(),"all grounded"));
        run("vine population and ratio limits reject weak samples", () -> {
            eq(0,evaluate(vines(7,7)).score(),"seven ends");
            eq(0,evaluate(vines(8,5)).score(),"five of eight");
            eq(10,evaluate(vines(8,6)).score(),"six of eight");
        });
        run("hanging vines above air do not score", () -> eq(0,evaluate(vines(12,0)).score(),"hanging"));
        run("unknown below vines does not count as stone", () -> {
            Scene s=vines(8,8);for(int i=0;i<8;i++)s.set(4+i,34,8,UNKNOWN);
            eq(0,evaluate(s).score(),"unknown support");
        });
        run("natural generated kelp ages 20 through 23 below water do not score", () -> {
            for(int age=20;age<=23;age++) eq(0,evaluate(kelp(12,0,0,age)).score(),"worldgen age"+age);
        });
        run("surface kelp requires twelve tips and 80 percent air above", () -> {
            eq(0,evaluate(kelp(11,11,0,22)).score(),"eleven tips");
            eq(0,evaluate(kelp(12,9,0,22)).score(),"nine of twelve air tips");
            eq(15,evaluate(kelp(12,10,0,22)).score(),"ten of twelve air tips");
        });
        run("kelp age 24 or 25 requires substantial population", () -> {
            eq(0,evaluate(kelp(12,0,8,24)).score(),"eight aged tips");
            eq(15,evaluate(kelp(12,0,9,24)).score(),"nine age24 tips");
            eq(15,evaluate(kelp(12,0,12,25)).score(),"twelve age25 tips");
        });
        run("surface and mature kelp together cap at 20", () -> eq(20,evaluate(kelp(12,12,12,25)).score(),"shared cap"));
        run("isolated short kelp without water context is ignored", () -> {
            Scene s=new Scene();for(int i=0;i<12;i++)s.set(3+i,65,8,cell(KELP,25,0));
            eq(0,evaluate(s).score(),"not a kelp bed");
        });
        run("growth is frozen to first observed scene while current edit evidence remains live", () -> {
            Scene first=kelp(12,0,0,22),current=kelp(12,12,12,25);
            current.fill(6,28,6,10,32,10,DEEPSLATE);current.set(8,30,8,HORIZONTAL_DEEPSLATE);
            Evaluation e=SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of());
            eq(30,e.score(),"waiting should not grow kelp score");
        });
        run("first observed mature kelp evidence remains stable after current growth changes", () -> {
            Scene first=kelp(12,0,12,25),current=kelp(12,0,0,22);
            Evaluation e=SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of());
            eq(15,e.score(),"first age observation is retained");
        });
        run("first observed vine endings do not acquire points from waiting", () -> {
            Scene first=vines(8,0),current=vines(8,8);
            eq(0,SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).score(),"vines frozen");
        });
    }

    private static void geodes() {
        run("large complete geode missing budding amethyst scores 25", () -> eq(25,evaluate(geode(0,0,CLUSTER)).score(),"missing budding"));
        run("mature crystal population scores 15", () -> eq(15,evaluate(geode(15,15,CLUSTER)).score(),"mature geode"));
        run("young crystal population does not score maturity", () -> eq(0,evaluate(geode(15,15,SMALL_BUD)).score(),"young geode"));
        run("sparse crystals no longer contribute any suspicion", () -> eq(0,evaluate(geode(25,0,CLUSTER)).score(),"sparse check removed"));
        run("sparse crystals stay unscored regardless of exposed growth medium", () -> {
            for(int medium:new int[]{AIR,WATER,FLOWING_WATER,WATERLOGGED_OTHER}) {
                Scene s=geode(25,0,CLUSTER);s.fill(6,30,6,10,30,10,medium);
                eq(0,evaluate(s).score(),"no sparse score in medium"+medium);
            }
        });
        run("isolated amethyst without calcite and smooth basalt is ignored", () -> {
            Scene s=new Scene();s.fill(5,29,5,11,35,11,AMETHYST);eq(0,evaluate(s).score(),"amethyst construction");
        });
        run("incomplete geode data cannot establish missing budding", () -> {
            Scene s=geode(0,0,CLUSTER);s.set(8,32,11,UNKNOWN);eq(0,evaluate(s).score(),"unknown geode face");
        });
        run("small amethyst sample cannot establish missing budding", () -> {
            Scene s=new Scene();s.fill(5,30,5,7,32,7,AMETHYST);s.fill(4,29,4,8,29,8,CALCITE);
            eq(0,evaluate(s).score(),"small geode sample");
        });
        run("first geode growth does not gain maturity while waiting", () -> {
            Scene first=geode(15,15,SMALL_BUD),current=geode(15,15,CLUSTER);
            eq(0,SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).score(),"geode frozen");
        });
        run("wrong-facing buds are not attached crystals", () -> {
            Scene s=geode(15,15,CLUSTER);
            for(int i=0;i<15;i++)s.set(6+i%5,30,6+i/5,cell(CLUSTER,0,0));
            check(!evaluate(s).contributions().containsKey(Check.MATURE_GEODE),"crystals face away from attachment");
        });
    }

    private static void snapshots() {
        run("snapshot cells preserve unsigned state and negative chunk coordinates", () -> {
            int encoded=cell(CLUSTER,25,5);SuspiciousSnapshots.Chunk c=chunk(-1,-2,encoded,1);
            eq(encoded,c.cell(-15,3,-30),"unsigned short");eq(UNKNOWN,c.cell(1,3,-30),"wrong chunk");
            eq(UNKNOWN,c.cell(-15,-1,-30),"below floor");eq(UNKNOWN,c.cell(-15,32,-30),"exclusive top");
            long id=SuspiciousSnapshots.key(-1,-2);eq(-1,SuspiciousSnapshots.x(id),"chunk x");eq(-2,SuspiciousSnapshots.z(id),"chunk z");
        });
        run("snapshot views copy their map and distinguish absent chunks", () -> {
            Map<Long,SuspiciousSnapshots.Chunk> backing=new HashMap<>();backing.put(0L,chunk(0,0,STONE,1));
            SuspiciousSnapshots.View view=new SuspiciousSnapshots.View(backing,0,32);backing.clear();
            eq(STONE,view.cell(1,1,1),"map copy");eq(UNKNOWN,view.cell(16,1,1),"absent neighbor");
            eq(Integer.MIN_VALUE,view.surfaceY(16,1),"absent surface");boolean immutable=false;
            try{view.chunks().clear();}catch(UnsupportedOperationException expected){immutable=true;}check(immutable,"immutable map");
        });
        run("unknown surfaces cannot fabricate marker heights and fill only once", () -> {
            int[] heights=new int[256];Arrays.fill(heights,Integer.MIN_VALUE);
            var missing=new SuspiciousSnapshots.Chunk(0,0,0,32,new short[][]{{(short)UNKNOWN},{(short)UNKNOWN}},heights,1,1);
            eq(Integer.MIN_VALUE,missing.representativeSurface(),"all unknown surface");
            int[] partlyKnown=heights.clone();partlyKnown[0]=5;
            var partial=new SuspiciousSnapshots.Chunk(0,0,0,32,new short[][]{{(short)UNKNOWN},{(short)UNKNOWN}},partlyKnown,1,1);
            eq(5,partial.representativeSurface(),"ignore unknown sentinel in percentile");
            var cache=new SuspiciousSnapshots.Cache();cache.put(partial);cache.put(chunk(0,0,STONE,2));
            var filled=cache.first().get(0L);eq(5,filled.surface(0,0),"known original surface retained");
            eq(20,filled.surface(1,0),"unknown surface filled from next snapshot");
            eq(20,filled.representativeSurface(),"known percentile after fill");
        });
        run("cache retains the first growth values while live values advance", () -> {
            var cache=new SuspiciousSnapshots.Cache();var first=chunk(0,0,cell(KELP,22,0),1);cache.put(first);
            cache.put(chunk(0,0,cell(KELP,25,0),2));
            eq(22,age(cache.first().get(0L).cell(1,1,1)),"first age");
            eq(25,age(cache.live(0L).cell(1,1,1)),"live age");
            check(cache.first().get(0L)==first,"known first snapshot keeps identity");
        });
        run("unknown initial cells fill once without overwriting known first cells", () -> {
            short[] partial=new short[4096];partial[0]=(short)cell(KELP,22,0);
            int[] heights=new int[256];Arrays.fill(heights,20);
            var old=new SuspiciousSnapshots.Chunk(0,0,0,32,new short[][]{partial,null},heights,111,1);
            var cache=new SuspiciousSnapshots.Cache();cache.put(old);cache.put(chunk(0,0,cell(KELP,25,0),2));
            var filled=cache.first().get(0L);
            eq(22,age(filled.cell(0,0,0)),"known remains first");eq(25,age(filled.cell(1,0,0)),"unknown cell filled");
            eq(25,age(filled.cell(1,20,1)),"unknown section filled");
            check(filled.observedMillis==111,"original observation time");
            cache.put(chunk(0,0,cell(KELP,24,0),3));eq(25,age(cache.first().get(0L).cell(1,0,0)),"filled only once");
        });
        run("unload and revisit retain the original growth baseline", () -> {
            var cache=new SuspiciousSnapshots.Cache();cache.put(chunk(0,0,cell(KELP,22,0),1));cache.unload(0L);
            check(cache.live(0L)==null,"live removed");check(cache.first().containsKey(0L),"first retained");
            cache.put(chunk(0,0,cell(KELP,25,0),2));eq(22,age(cache.first().get(0L).cell(1,1,1)),"revisit frozen");
        });
        run("suspending live terrain preserves session growth history", () -> {
            var cache=new SuspiciousSnapshots.Cache();cache.put(chunk(0,0,cell(KELP,22,0),1));cache.clearLive();
            eq(0,cache.size(),"live capture cleared");check(cache.first().containsKey(0L),"first retained for respawn");
            cache.put(chunk(0,0,cell(KELP,25,0),2));eq(22,age(cache.first().get(0L).cell(1,1,1)),"no aged reseed after suspension");
        });
        run("old snapshots evict without reseeding later aged growth", () -> {
            var cache=new SuspiciousSnapshots.Cache();cache.put(chunk(0,0,cell(KELP,22,0),1));
            for(int x=1;x<=SuspiciousSnapshots.Cache.MAX_CHUNKS;x++)cache.put(chunk(x,0,STONE,x+1));
            check(cache.live(0L)==null&&!cache.first().containsKey(0L),"old observation evicted");
            cache.put(chunk(0,0,cell(KELP,25,0),999));
            check(cache.live(0L)!=null,"revisit available for current checks");
            check(!cache.first().containsKey(0L),"eviction does not seed aged growth");
            check(cache.size()<=SuspiciousSnapshots.Cache.MAX_CHUNKS,"chunk cap");
        });
        run("recently refreshed live snapshots survive insertion-order pressure", () -> {
            var cache=new SuspiciousSnapshots.Cache();for(int x=0;x<512;x++)cache.put(chunk(x,0,STONE,x));
            cache.put(chunk(0,0,DEEPSLATE,600));cache.put(chunk(512,0,STONE,601));
            check(cache.live(0L)!=null,"refreshed key retained");check(cache.live(SuspiciousSnapshots.key(1,0))==null,"oldest live evicted");
        });
        run("snapshot byte accounting shares identical first and live objects", () -> {
            var cache=new SuspiciousSnapshots.Cache();var c=chunk(0,0,STONE,1);cache.put(c);
            check(cache.bytes()==c.bytes(),"same object counted once");cache.unload(0L);
            check(cache.bytes()==c.bytes(),"first remains counted");
            var replacement=chunk(0,0,DEEPSLATE,2);cache.put(replacement);
            check(cache.bytes()==c.bytes()+replacement.bytes(),"distinct snapshots counted");
        });
        run("snapshot cache enforces the 64 MiB data budget", () -> {
            var cache=new SuspiciousSnapshots.Cache();
            for(int x=0;x<400;x++) {
                short[][] sections=new short[24][4096];for(short[] section:sections)Arrays.fill(section,(short)STONE);
                int[] heights=new int[256];Arrays.fill(heights,64);
                cache.put(new SuspiciousSnapshots.Chunk(x,0,0,384,sections,heights,x,x));
                check(cache.bytes()<=SuspiciousSnapshots.Cache.MAX_BYTES,"bounded bytes");
            }
            check(cache.size()<400,"byte pressure evicted entries");
        });
        run("full session history fails conservatively and reset permits fresh observations", () -> {
            var cache=new SuspiciousSnapshots.Cache();
            for(int x=0;x<SuspiciousSnapshots.Cache.MAX_SEEN;x++)cache.put(chunk(x,0,STONE,x));
            check(cache.historyFull(),"history capacity reached");int next=SuspiciousSnapshots.Cache.MAX_SEEN;
            cache.put(chunk(next,0,cell(KELP,25,0),next));
            check(!cache.first().containsKey(SuspiciousSnapshots.key(next,0)),"no new growth history after cap");
            cache.clear();eq(0,cache.size(),"live cleared");check(cache.first().isEmpty()&&!cache.historyFull()&&cache.bytes()==0,"all session state cleared");
            cache.put(chunk(0,0,cell(KELP,25,0),next+1));check(cache.first().containsKey(0L),"new session can observe again");
        });
    }

    private static void growthObservations() {
        run("qualified kelp observations use actual tips and deduplicate age plus surface", () -> {
            Scene s=kelp(12,12,12,25);
            List<GrowthObservation> observations=evaluate(s).growthObservations();
            eq(12,observations.size(),"one sample per stalk");
            Set<Long> unique=new HashSet<>();
            for(GrowthObservation observation:observations) {
                eq(KELP,kind(s.cell(observation.blockX(),observation.blockY(),observation.blockZ())),"actual tip");
                check(observation.sampleId()==featureId(new Pos(observation.blockX(),observation.blockY(),observation.blockZ())),"stable block identity");
                check(unique.add(observation.sampleId()),"unique physical sample");
            }
        });
        run("neighbor-only growth is attributed to its actual neighbor chunk", () -> {
            Scene s=kelp(12,12,12,25,16,16);
            List<GrowthObservation> observations=evaluate(s).growthObservations();
            eq(12,observations.size(),"neighbor tips retained");
            for(GrowthObservation observation:observations) {
                eq(1,Math.floorDiv(observation.blockX(),16),"actual chunk x");
                eq(1,Math.floorDiv(observation.blockZ(),16),"actual chunk z");
            }
            Evaluation adjacent=SuspiciousAnalysis.evaluate(s,1,1,summaries(s),ALL,List.of());
            check(observations.equals(adjacent.growthObservations()),"overlapping scoring windows preserve physical sample identities");
        });
        run("all qualifying separated populations emit observations", () -> {
            Scene s=kelp(12,12,12,25,-16,-16);
            s.cells.putAll(kelp(12,12,12,25,16,16).cells);
            eq(24,evaluate(s).growthObservations().size(),"second local population is not lost to an early return");
        });
        run("each separated kelp bed retains both score kinds after positional dedup", () -> {
            Scene s=kelp(12,12,12,25,-16,-16);
            s.cells.putAll(kelp(12,12,12,25,16,16).cells);
            Evaluation evaluation=evaluate(s);
            eq(1,count(evaluation.findings(),Check.MATURE_KELP_AGE),"findings retain their original first-population score contract");
            eq(2,evaluation.growthChecks().size(),"both actual sample chunks retained");
            for(long chunk:new long[]{chunkId(-1,-1),chunkId(1,1)}) {
                check(evaluation.growthChecks().get(chunk).equals(EnumSet.of(Check.SURFACE_KELP,Check.MATURE_KELP_AGE)),"both growth kinds in actual bed");
            }
            check(!evaluation.growthChecks().containsKey(chunkId(0,0)),"no kind assigned to evaluation center");
            List<Finding> laterBed=new ArrayList<>();
            for(Check kind:evaluation.growthChecks().get(chunkId(1,1))) laterBed.add(finding(kind,7,1,1));
            eq(20,score(laterBed,ALL).score(),"second bed keeps the full kelp-family score independently");
        });
        run("growth kinds follow positive tips rather than an immature population seed", () -> {
            Scene s=new Scene();s.fill(12,60,1,25,65,12,WATER);
            for(int i=0;i<12;i++) {
                int x=14+i%4*3,z=3+i/4*3;
                s.set(x,62,z,KELP_PLANT);s.set(x,63,z,KELP_PLANT);
                s.set(x,64,z,cell(KELP,i%4==0?22:25,0));
            }
            Evaluation evaluation=evaluate(s);
            eq(15,evaluation.score(),"nine aged tips qualify");
            eq(1,evaluation.growthChecks().size(),"only actual positive chunk");
            check(evaluation.growthChecks().get(chunkId(1,0)).equals(Set.of(Check.MATURE_KELP_AGE)),"aged tips belong to neighbor chunk");
            check(!evaluation.growthChecks().containsKey(chunkId(0,0)),"immature seed chunk is not positive evidence");
        });
        run("only qualified positive growth samples enter activity evidence", () -> {
            eq(6,evaluate(vines(8,6)).growthObservations().size(),"six grounded ends only");
            eq(0,evaluate(vines(7,7)).growthObservations().size(),"small vine sample rejected");
            eq(0,evaluate(kelp(11,11,11,25)).growthObservations().size(),"small kelp sample rejected");
            eq(0,evaluate(kelp(12,0,8,24)).growthObservations().size(),"low mature ratio rejected");
            eq(15,evaluate(geode(15,15,CLUSTER)).growthObservations().size(),"actual mature crystals");
            eq(0,evaluate(geode(0,0,CLUSTER)).growthObservations().size(),"absence is not positive growth evidence");
            eq(0,evaluate(rotated(8,30,8)).growthObservations().size(),"replacement is not growth evidence");
        });
        run("activity observations respect frozen first snapshots and check toggles", () -> {
            Scene first=kelp(12,0,0,22),current=kelp(12,12,12,25);
            eq(0,SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).growthObservations().size(),"waiting cannot add activity samples");
            check(SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).growthChecks().isEmpty(),"waiting cannot add growth kinds");
            first=current;current=kelp(12,0,0,22);
            eq(12,SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).growthObservations().size(),"original mature observations stay frozen");
            check(SuspiciousAnalysis.evaluate(current,first,0,0,summaries(current),summaries(first),ALL,List.of()).growthChecks()
                .get(chunkId(0,0)).equals(EnumSet.of(Check.SURFACE_KELP,Check.MATURE_KELP_AGE)),"both original growth kinds stay frozen");
            eq(0,SuspiciousAnalysis.evaluate(first,0,0,summaries(first),EnumSet.of(Check.MATURE_GEODE),List.of()).growthObservations().size(),"disabled kelp checks cannot seed estimator");
            List<GrowthObservation> observations=evaluate(first).growthObservations();
            boolean refused=false;try{observations.clear();}catch(UnsupportedOperationException expected){refused=true;}
            check(refused,"growth evidence is immutable");
        });
        run("growth-kind publication deeply copies immutable bounded chunk sets", () -> {
            EnumSet<Check> sourceSet=EnumSet.of(Check.SURFACE_KELP);
            Map<Long,Set<Check>> sourceMap=new HashMap<>();sourceMap.put(chunkId(0,0),sourceSet);
            Evaluation result=new Evaluation(0,Map.of(),List.of(),List.of(),sourceMap);
            sourceSet.add(Check.MATURE_KELP_AGE);sourceMap.clear();
            check(result.growthChecks().get(chunkId(0,0)).equals(Set.of(Check.SURFACE_KELP)),"deep copy protects source changes");
            boolean refused=false;try{result.growthChecks().clear();}catch(UnsupportedOperationException expected){refused=true;}
            check(refused,"chunk map immutable");
            refused=false;try{result.growthChecks().get(chunkId(0,0)).clear();}catch(UnsupportedOperationException expected){refused=true;}
            check(refused,"chunk check set immutable");
            check(new Evaluation(0,Map.of(),List.of()).growthChecks().isEmpty(),"three-argument compatibility");
            check(new Evaluation(0,Map.of(),List.of(),List.of()).growthChecks().isEmpty(),"four-argument compatibility");
        });
        run("large growth populations have a deterministic bounded sample", () -> {
            Scene s=new Scene();s.fill(-15,30,-15,30,33,30,WATER);
            for(int x=-14;x<=29;x++)for(int z=-14;z<=29;z++) {
                s.set(x,30,z,KELP_PLANT);s.set(x,31,z,KELP_PLANT);
                s.set(x,32,z,cell(KELP,25,0));s.set(x,33,z,AIR);
            }
            Evaluation first=evaluate(s),second=evaluate(s);
            eq(MAX_GROWTH_OBSERVATIONS_PER_WINDOW,first.growthObservations().size(),"sample cap");
            eq(9,first.growthChecks().size(),"every actual chunk survives the positional sample cap");
            for(Set<Check> kinds:first.growthChecks().values()) {
                check(kinds.equals(EnumSet.of(Check.SURFACE_KELP,Check.MATURE_KELP_AGE)),"capped sample preserves both kinds");
                check(kinds.size()<=4,"growth-kind bound");
            }
            check(first.growthObservations().equals(second.growthObservations()),"repeat sample stability");
            long previous=0;boolean started=false;
            for(GrowthObservation observation:first.growthObservations()) {
                long rank=growthSampleRank(observation.sampleId());
                check(!started||Long.compareUnsigned(previous,rank)<=0,"coordinate-hash ordering");
                previous=rank;started=true;
            }
        });
    }

    private static SuspiciousSnapshots.Chunk chunk(int x,int z,int value,long revision) {
        int[] surface=new int[256];Arrays.fill(surface,20);
        return new SuspiciousSnapshots.Chunk(x,z,0,32,new short[][]{{(short)value},{(short)value}},surface,revision,revision);
    }

    private static void windows() {
        run("eight-neighbor-only evidence can qualify empty center", () -> {
            Scene s=rotated(20,30,20);Evaluation e=evaluate(s);
            eq(30,e.score(),"diagonal neighbor");check(e.qualifies(30),"neighbor qualifies");
        });
        run("negative chunk coordinates participate correctly", () -> eq(30,evaluate(rotated(-8,30,-8)).score(),"negative diagonal"));
        run("evidence outside the nine-chunk window is ignored", () -> {
            Scene s=new Scene(-32,63);s.fill(38,28,6,42,32,10,DEEPSLATE);s.set(40,30,8,HORIZONTAL_DEEPSLATE);
            List<ChunkSummary> all=summaries(s);all.add(summarize(s,2,0));
            eq(0,SuspiciousAnalysis.evaluate(s,0,0,all,ALL,List.of()).score(),"outside summary");
        });
        run("geometry outside the nine-chunk window is ignored", () -> {
            Scene s=new Scene();eq(0,SuspiciousAnalysis.evaluate(s,0,0,summaries(s),ALL,
                List.of(finding(Check.BURIED_ACCESS_PATH,1,2,0))).score(),"outside geometry");
        });
        run("all unknown terrain contributes nothing", () -> {
            Scene s=new Scene();s.defaultKind=UNKNOWN;eq(0,evaluate(s).score(),"unknown world");
        });
        run("summaries are immutable", () -> {
            ChunkSummary summary=summarize(rotated(8,30,8),0,0);boolean refused=false;
            try {summary.candidates().add(new Pos(0,0,0));} catch(UnsupportedOperationException expected) {refused=true;}
            check(refused,"candidate list must be immutable");
        });
        run("candidate overflow is bounded and marks summary incomplete", () -> {
            Scene s=new Scene();s.fill(0,0,0,15,40,15,COBBLE);ChunkSummary summary=summarize(s,0,0);
            eq(8192,summary.candidates().size(),"candidate cap");check(!summary.complete(),"overflow incomplete");
            check(summary.candidateBytes()<=48+8192L*4,"packed candidate storage");
        });
        run("packed candidate coordinates preserve negative chunks and negative heights", () -> {
            Scene s=new Scene(-32,31);s.bottom=-64;s.set(-17,-31,-19,HORIZONTAL_DEEPSLATE);
            ChunkSummary summary=summarize(s,-2,-2);
            check(summary.candidates().contains(new Pos(-17,-31,-19)),"signed coordinate decode");
        });
        run("dense ordinary dirt does not consume the cap before higher kelp evidence", () -> {
            Scene s=kelp(12,12,12,25);s.fill(0,0,0,15,58,15,DIRT);
            ChunkSummary summary=summarize(s,0,0);
            check(summary.complete(),"natural dirt filtered without overflow");
            check(summary.candidates().contains(new Pos(3,64,3)),"higher kelp retained");
            eq(20,evaluate(s).score(),"kelp after dirt deposit");
        });
        run("border dirt candidates survive cached adjacent-context changes", () -> {
            Scene s=new Scene();s.defaultKind=DIRT;
            ChunkSummary summary=summarize(s,0,0);
            check(summary.candidates().contains(new Pos(15,32,15)),"known neighbor border retained");
            s.set(16,32,15,UNKNOWN);summary=summarize(s,0,0);
            check(summary.candidates().contains(new Pos(15,32,15)),"unknown neighbor border retained");
        });
        run("a missing chunk is not an empty complete chunk", () -> {
            Scene s=new Scene();s.fill(0,0,0,15,95,15,UNKNOWN);ChunkSummary summary=summarize(s,0,0);
            check(!summary.complete(),"unknown incomplete");eq(0,summary.candidates().size(),"no unknown evidence");
        });
        run("summarization responds to worker cancellation", () -> {
            boolean stopped=false;Thread.currentThread().interrupt();
            try {summarize(new Scene(),0,0);}catch(CancellationException expected){stopped=true;}
            finally{Thread.interrupted();}
            check(stopped,"interrupted worker stops before scanning");
        });
    }

    private static void geometry() {
        run("open 1x1 shaft reaching floor qualifies once", () -> eq(1,count(geometry(shaft(1,false)),Check.OPEN_SHAFTS),"one shaft"));
        run("open 3x1 shaft qualifies in either rotation", () -> {
            eq(1,count(geometry(shaft(3,false)),Check.OPEN_SHAFTS),"x width3");
            eq(1,count(geometry(shaft(3,true)),Check.OPEN_SHAFTS),"z width3");
        });
        run("narrow shaft follows bends and landing", () -> {
            Scene s=new Scene();s.fill(8,36,8,8,65,8,AIR);s.fill(8,36,8,10,36,8,AIR);s.fill(10,6,8,10,36,8,AIR);
            eq(1,count(geometry(s),Check.OPEN_SHAFTS),"bend to lower shaft");
        });
        run("shaft follows independent X and Z bends", () -> {
            Scene s=new Scene();s.fill(8,44,8,8,65,8,AIR);s.fill(8,44,8,10,44,8,AIR);
            s.fill(10,24,8,10,44,8,AIR);s.fill(10,24,8,10,24,10,AIR);s.fill(10,6,10,10,24,10,AIR);
            eq(1,count(geometry(s),Check.OPEN_SHAFTS),"two horizontal axes");
        });
        run("diagonal contact without a face connection is not a continuous shaft", () -> {
            Scene s=new Scene();s.fill(8,36,8,8,65,8,AIR);s.fill(9,6,8,9,35,8,AIR);
            eq(0,count(geometry(s),Check.OPEN_SHAFTS),"diagonal gap");
        });
        run("shaft traversal fraction and configured floor are respected", () -> {
            Scene s=new Scene();s.baseSurface=40;s.fill(8,8,8,8,41,8,AIR);
            eq(1,count(geometry(s),Check.OPEN_SHAFTS),"exact80percent");
            s=new Scene();s.baseSurface=39;s.fill(8,8,8,8,40,8,AIR);
            eq(0,count(geometry(s),Check.OPEN_SHAFTS),"under80percent");
            s=new Scene();s.bottom=-64;s.fill(8,-58,8,8,65,8,AIR);
            eq(1,count(geometry(s),Check.OPEN_SHAFTS),"negative configured floor");
        });
        run("neighbor-only open shaft is found", () -> {
            Scene s=new Scene();s.fill(24,6,24,24,65,24,AIR);eq(1,count(geometry(s),Check.OPEN_SHAFTS),"diagonal mouth");
        });
        run("multiple qualifying shafts deduct only once", () -> {
            Scene s=shaft(1,false);s.fill(24,6,24,24,65,24,AIR);
            eq(1,count(geometry(s),Check.OPEN_SHAFTS),"deduction once");
        });
        run("sealed shaft is not open", () -> {
            Scene s=shaft(1,false);s.set(8,64,8,STONE);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"sealed");
        });
        run("shallow shaft and shaft stopping high above floor are ignored", () -> {
            Scene s=new Scene();s.fill(8,45,8,8,65,8,AIR);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"depth20");
            s=new Scene();s.fill(8,20,8,8,65,8,AIR);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"not near floor");
        });
        run("wide caves and ravines do not qualify as narrow shafts", () -> {
            Scene s=new Scene();s.fill(7,6,7,9,65,9,AIR);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"3x3");
            s=new Scene();s.fill(5,6,8,11,65,8,AIR);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"7x1 ravine");
        });
        run("water in a shaft prevents deduction", () -> {
            Scene s=shaft(1,false);s.set(8,35,8,WATER);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"water");
        });
        run("unknown shaft cells cannot establish a complete route", () -> {
            Scene s=shaft(1,false);s.set(8,35,8,UNKNOWN);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"unloaded route");
        });
        run("window-clipped open route cannot qualify", () -> {
            Scene s=new Scene();s.fill(-16,6,8,-16,65,8,AIR);eq(0,count(geometry(s),Check.OPEN_SHAFTS),"clipped boundary");
        });
        run("geometry advance honors deadline and work-step budget", () -> {
            Scene s=shaft(1,false);SuspiciousGeometry.Job job=new SuspiciousGeometry.Job(s,0,0,summaries(s));
            eq(0,job.advance(512,System.nanoTime()-1),"expired deadline");
            eq(0,job.advance(0,Long.MAX_VALUE),"zero budget");
            eq(0,job.advance(-1,Long.MAX_VALUE),"negative budget");
            check(job.findings().isEmpty(),"unfinished route contributes nothing");
            int steps=0,calls=0;
            while(!job.isDone()&&calls++<20000) {int used=job.advance(7,Long.MAX_VALUE);check(used>=0&&used<=7,"bounded work slice");steps+=used;}
            check(job.isDone(),"finite work");check(steps>0,"actual work happened");
            eq(1,count(job.findings(),Check.OPEN_SHAFTS),"budgeted result stable");
        });
        run("shaft candidate exhaustion is inconclusive at 4096 cells", () -> {
            Scene s=new Scene();s.baseSurface=4200;s.height=4300;s.fill(8,6,8,8,4201,8,AIR);
            SuspiciousGeometry.Job job=new SuspiciousGeometry.Job(s,0,0,List.of());int calls=0;
            while(!job.isDone()&&calls++<100)job.advance(512,Long.MAX_VALUE);
            check(job.isDone(),"exhausted candidate terminates");
            check(job.workSteps()>4096,"long route actually reached candidate limit");
            check(job.workSteps()<=6500,"one shaft cannot exceed4096nodes plus2304surfacecolumns");
            eq(0,count(job.findings(),Check.OPEN_SHAFTS),"exhausted route yields no deduction");
        });
        run("six embedded anomalous blocks in a vertical shaft score buried access", () -> {
            Scene s=new Scene();s.fill(8,20,8,8,25,8,COBBLE);eq(1,count(geometry(s),Check.BURIED_ACCESS_PATH),"filled shaft");
        });
        run("embedded tunnel and staircase replacements score buried access", () -> {
            Scene s=new Scene();s.fill(5,30,8,12,30,8,COBBLED_DEEPSLATE);eq(1,count(geometry(s),Check.BURIED_ACCESS_PATH),"tunnel");
            s=new Scene();for(int i=0;i<7;i++){s.set(5+i,20+i,8,COBBLE);s.set(6+i,20+i,8,COBBLE);}
            eq(1,count(geometry(s),Check.BURIED_ACCESS_PATH),"stairs");
        });
        run("short fill, unmodified rock and broad dirt deposits are not buried routes", () -> {
            Scene s=new Scene();s.fill(8,20,8,8,24,8,COBBLE);eq(0,count(geometry(s),Check.BURIED_ACCESS_PATH),"five cells");
            eq(0,count(geometry(new Scene()),Check.BURIED_ACCESS_PATH),"no material anomaly");
            s=new Scene();s.fill(5,20,5,11,26,11,DIRT);eq(0,count(geometry(s),Check.BURIED_ACCESS_PATH),"natural dirt blob");
        });
        run("natural structure, fluid and unknown neighbors exclude a buried route", () -> {
            for(int blocker:new int[]{STRUCTURE,WATER,LAVA,UNKNOWN}) {
                Scene s=new Scene();s.fill(8,20,8,8,25,8,COBBLE);s.fill(9,20,8,9,25,8,blocker);
                eq(0,count(geometry(s),Check.BURIED_ACCESS_PATH),"blocked context"+blocker);
            }
        });
        run("buried geometry does not search beyond the 3x3 window", () -> {
            Scene s=new Scene(-32,63);s.fill(40,20,8,40,30,8,COBBLE);List<ChunkSummary> chunks=summaries(s);chunks.add(summarize(s,2,0));
            SuspiciousGeometry.Job job=new SuspiciousGeometry.Job(s,0,0,chunks);int calls=0;
            while(!job.isDone()&&calls++<100)job.advance(512,Long.MAX_VALUE);
            check(job.isDone(),"finite outside scan");eq(0,count(job.findings(),Check.BURIED_ACCESS_PATH),"outside candidate");
        });
    }

    private static Scene rotated(int x,int y,int z) {
        Scene s=new Scene();s.fill(x-2,y-2,z-2,x+2,y+2,z+2,DEEPSLATE);s.set(x,y,z,HORIZONTAL_DEEPSLATE);return s;
    }
    private static Scene soil(int surround) {
        Scene s=new Scene();s.fill(4,64,4,11,64,11,surround);s.fill(7,64,7,8,64,8,DIRT);return s;
    }
    private static Scene vines(int count,int grounded) {
        Scene s=new Scene();
        for(int i=0;i<count;i++) {s.fill(4+i,35,8,4+i,37,8,VINE);s.set(4+i,34,8,i<grounded?STONE:AIR);}
        return s;
    }
    private static Scene kelp(int count,int exposed,int mature,int matureAge) {
        return kelp(count,exposed,mature,matureAge,0,0);
    }
    private static Scene kelp(int count,int exposed,int mature,int matureAge,int offsetX,int offsetZ) {
        Scene s=new Scene();s.fill(offsetX+1,60,offsetZ+1,offsetX+15,65,offsetZ+15,WATER);
        for(int i=0;i<count;i++) {int x=offsetX+3+(i%4)*3,z=offsetZ+3+(i/4)*3;
            s.set(x,62,z,KELP_PLANT);s.set(x,63,z,KELP_PLANT);s.set(x,64,z,cell(KELP,i<mature?matureAge:22,0));
            s.set(x,65,z,i<exposed?AIR:WATER);
        }
        return s;
    }
    private static Scene geode(int budding,int crystals,int crystalKind) {
        Scene s=new Scene();
        for(int radius=5;radius>=3;radius--) {
            int material=radius==5?SMOOTH_BASALT:radius==4?CALCITE:AMETHYST;
            for(int x=-radius;x<=radius;x++)for(int y=-radius;y<=radius;y++)for(int z=-radius;z<=radius;z++)
                if(Math.max(Math.max(Math.abs(x),Math.abs(y)),Math.abs(z))==radius)s.set(8+x,32+y,8+z,material);
        }
        s.fill(6,30,6,10,34,10,AIR);
        for(int i=0;i<budding;i++) {int x=6+i%5,z=6+i/5;s.set(x,29,z,BUDDING);if(i<crystals)s.set(x,30,z,cell(crystalKind,0,1));}
        return s;
    }
    private static Scene shaft(int width,boolean zAxis) {
        Scene s=new Scene();for(int d=0;d<width;d++)s.fill(8+(zAxis?0:d),6,8+(zAxis?d:0),8+(zAxis?0:d),65,8+(zAxis?d:0),AIR);return s;
    }
    private static List<Finding> geometry(Scene s) {
        SuspiciousGeometry.Job job=new SuspiciousGeometry.Job(s,0,0,summaries(s));int calls=0;
        while(!job.isDone()&&calls++<10000) {int used=job.advance(512,Long.MAX_VALUE);check(used>=0&&used<=512,"geometry work bound");}
        check(job.isDone(),"geometry completed in finite slices");return job.findings();
    }
    private static int count(Collection<Finding> values,Check check) {int n=0;for(Finding f:values)if(f.check()==check)n++;return n;}
    private static List<ChunkSummary> summaries(Scene s) {
        List<ChunkSummary> list=new ArrayList<>();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)list.add(summarize(s,x,z));return list;
    }
    private static Evaluation evaluate(Scene s) {return SuspiciousAnalysis.evaluate(s,0,0,summaries(s),ALL,List.of());}
    private static Finding finding(Check check,long id,int x,int z) {return new Finding(check,id,x,z);}
    private static int scored(Check... checks) {List<Finding> list=new ArrayList<>();for(Check c:checks)list.add(finding(c,1,0,0));return score(list,ALL).score();}
    private static void eq(int expected,int actual,String message) {check(expected==actual,message+": expected "+expected+", got "+actual);}
    private static void check(boolean condition,String message) {assertions++;if(!condition)throw new AssertionError(message);}
    private static void run(String name,Runnable test) {
        try {test.run();passed++;System.out.println("PASS "+name);}catch(Throwable failure){failed++;System.out.println("FAIL "+name+": "+failure);}
    }
    private static final class Scene implements Terrain {
        final Map<Long,Integer> cells=new HashMap<>();
        final int minimum,maximum;
        int defaultKind=STONE;
        int baseSurface=64,height=96,bottom=0;
        Scene(){this(-16,31);}
        Scene(int minimum,int maximum){this.minimum=minimum;this.maximum=maximum;}
        public int floorY(){return bottom;}
        public int topY(){return height;}
        public int cell(int x,int y,int z){
            if(x<minimum||x>maximum||z<minimum||z>maximum||y<floorY()||y>=topY())return UNKNOWN;
            Integer found=cells.get(key(x,y,z));return found!=null?found:defaultKind==UNKNOWN?UNKNOWN:y<=baseSurface?defaultKind:AIR;
        }
        public int surfaceY(int x,int z){
            if(x<minimum||x>maximum||z<minimum||z>maximum)return Integer.MIN_VALUE;
            for(int y=topY()-1;y>=floorY();y--) {int kind=kind(cell(x,y,z));if(kind==UNKNOWN)return Integer.MIN_VALUE;if(kind!=AIR&&kind!=REPLACEABLE)return y;}
            return Integer.MIN_VALUE;
        }
        void set(int x,int y,int z,int value){cells.put(key(x,y,z),value);}
        void fill(int x0,int y0,int z0,int x1,int y1,int z1,int value){for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)set(x,y,z,value);}
        static long key(int x,int y,int z){return ((long)x&0xffffffL)<<40|((long)z&0xffffffL)<<16|((long)y&0xffffL);}
    }
}
