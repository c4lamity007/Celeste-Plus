import dev.c4lam.celeste.module.impl.visual.BlockEspScan;
import dev.c4lam.celeste.module.impl.visual.BlockEspScan.Point;
import dev.c4lam.celeste.module.impl.visual.BlockEspScan.Section;
import dev.c4lam.celeste.module.impl.visual.BlockEspScan.SectionSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Executes the production scheduler; no Minecraft or copied scheduler implementation. */
public final class BlockEspScanCheck {
    static int checks;
    static final Object WORLD = new Object();
    static final Point ORIGIN = new Point(8, 8, 8);
    static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    static Section section(Point p) { return new Section(Math.floorDiv(p.x(),16), Math.floorDiv(p.y(),16), Math.floorDiv(p.z(),16)); }
    static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    static final class Probe implements BlockEspScan.Probe {
        final Map<Section,List<Point>> points = new HashMap<>();
        final Map<Section,Object> revisions = new HashMap<>();
        final Set<Section> dense = new HashSet<>();
        final Set<Long> unavailable = new HashSet<>();
        final Set<Long> available = new HashSet<>();
        final Map<Section,Integer> perSection = new HashMap<>();
        boolean restrictChunks, indexed, allPositive;
        int palettes, collections, reads, chunkProbes, matches, indexChecks;
        void add(Point point) { points.computeIfAbsent(section(point), k -> new ArrayList<>()).add(point); }
        public boolean chunkLoaded(int x,int z) {
            chunkProbes++;
            return !unavailable.contains(key(x,z)) && (!restrictChunks || available.contains(key(x,z)));
        }
        public boolean loaded(Section section) {
            reads++;
            return !unavailable.contains(key(section.x(),section.z())) && (!restrictChunks || available.contains(key(section.x(),section.z())));
        }
        public Object revision(Section section) { return revisions.computeIfAbsent(section, k -> new Object()); }
        public boolean mayMatch(Section section) {
            palettes++;
            perSection.merge(section,1,Integer::sum);
            return allPositive || dense.contains(section) || !points.getOrDefault(section,List.of()).isEmpty();
        }
        public boolean indexed(Section section) { indexChecks++; return indexed; }
        public void collect(Section section, BlockEspScan.PointConsumer output) {
            collections++;
            if (dense.contains(section)) for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
                output.accept(new Point(section.x()*16+x, section.y()*16+y, section.z()*16+z));
            for(Point point:points.getOrDefault(section,List.of())) output.accept(point);
        }
        public boolean matches(Point point) { matches++; throw new AssertionError("Per-point polling is forbidden"); }
        void counters() { palettes=collections=reads=chunkProbes=matches=indexChecks=0; }
    }
    static void tick(BlockEspScan scan, Probe probe, Point center, int chunks, int radius, int bottom, int top) {
        probe.counters();
        scan.tick(WORLD,center,chunks,radius,bottom,top,probe);
        require(probe.palettes<=BlockEspScan.PALETTE_BUDGET,"Palette budget exceeded");
        require(probe.indexChecks<=probe.palettes+BlockEspScan.INDEX_RECHECK_BUDGET,"Indexed eligibility exceeded its collection-ready recheck budget");
        require(probe.collections<=(probe.indexed?BlockEspScan.INDEXED_SECTION_BUDGET:BlockEspScan.MATCHED_SECTION_BUDGET),"Collect budget exceeded");
        require(probe.reads<=BlockEspScan.DISCOVERY_BUDGET+256,"Section probe budget exceeded");
    }
    static void settle(BlockEspScan scan,Probe p,Point center,int chunks,int radius,int bottom,int top,int ticks) {
        for(int i=0;i<ticks;i++) tick(scan,p,center,chunks,radius,bottom,top);
    }
    static boolean contains(BlockEspScan scan,Point point) {
        Section wanted=section(point);
        for(SectionSnapshot snapshot:scan.snapshotSections()) {
            if(!snapshot.section().equals(wanted)) continue;
            for(int i=snapshot.nextLocalIndex(-1);i>=0;i=snapshot.nextLocalIndex(i)) if(snapshot.point(i).equals(point)) return true;
        }
        return false;
    }
    static int count(BlockEspScan scan) { return scan.snapshotSections().stream().mapToInt(SectionSnapshot::size).sum(); }
    static long distance(Section section,Point point) {
        long sum=0;
        int[] coords={section.x(),section.y(),section.z()}, values={point.x(),point.y(),point.z()};
        for(int i=0;i<3;i++) {
            long min=coords[i]*16L,max=min+15;
            long delta=values[i]<min?min-values[i]:values[i]>max?values[i]-max:0;
            sum+=delta*delta;
        }
        return sum;
    }
    static void steadyAndEvents() {
        BlockEspScan scan=new BlockEspScan(); Probe p=new Probe();
        Point a=new Point(-1,8,-1), b=new Point(24,8,8);
        p.add(a); p.add(b);
        settle(scan,p,ORIGIN,2,0,0,2,8);
        require(contains(scan,a)&&contains(scan,b),"Initial/negative-coordinate matches missing");
        List<SectionSnapshot> prior=scan.snapshotSections();
        for(int i=0;i<8;i++) {
            tick(scan,p,ORIGIN,2,0,0,2);
            require(p.collections==0&&p.palettes==0,"Unchanged sections scanned again");
            require(scan.snapshotSections()==prior,"Unchanged index was republished/copied");
        }
        p.points.remove(section(a)); scan.invalidate(section(a));
        require(!contains(scan,a),"Invalidation did not immediately discard old section mask");
        tick(scan,p,ORIGIN,2,0,0,2);
        require(p.palettes==1&&p.collections==0,"Negative update did not only recheck changed section");
        require(contains(scan,b),"Unrelated section changed");
        require(prior.stream().mapToInt(SectionSnapshot::size).sum()==2,"Old immutable snapshot mutated");
        p.add(a); scan.invalidate(section(a));
        tick(scan,p,ORIGIN,2,0,0,2);
        require(p.palettes==1&&p.collections==1&&contains(scan,a),"Changed positive section not recollected alone");
        p.points.remove(section(a)); p.revisions.put(section(a),new Object());
        settle(scan,p,ORIGIN,2,0,0,2,4);
        require(!contains(scan,a),"Replacement section identity was ignored");
        p.unavailable.add(key(section(b).x(),section(b).z())); scan.unloadChunk(section(b).x(),section(b).z());
        require(!contains(scan,b),"Unload retained stale mask");
        p.unavailable.clear(); scan.invalidateChunk(section(b).x(),section(b).z());
        settle(scan,p,ORIGIN,2,0,0,2,3);
        require(contains(scan,b),"Chunk-load event did not rediscover data");
        p.add(a); scan.invalidateChunk(-1,-1);
        settle(scan,p,ORIGIN,2,0,0,2,3);
        require(contains(scan,a),"Chunk invalidation lost matching data");
        p.points.clear(); scan.tick(new Object(),ORIGIN,2,0,0,2,p);
        require(count(scan)==0,"World change retained index");
        scan.clear(); require(scan.snapshotSections().isEmpty(),"Disable retained index");
        scan.tick(null,ORIGIN,2,0,0,2,p); require(scan.snapshotSections().isEmpty(),"Disconnect retained index");
    }
    static void masksAndRanges() {
        BlockEspScan scan=new BlockEspScan(); Probe p=new Probe();
        p.dense.add(new Section(0,0,0));p.dense.add(new Section(0,1,0));
        settle(scan,p,ORIGIN,0,0,0,2,3);
        require(count(scan)==8192,"Data index truncated dense matches to render budget");
        require(scan.snapshot().size()==1024,"Compatibility output not independently bounded");
        require(BlockEspScan.DRAW_LIMIT==512,"Draw budget changed unexpectedly");
        for(SectionSnapshot s:scan.snapshotSections()) {
            int count=0,last=-1;
            for(int i=s.nextLocalIndex(-1);i>=0;i=s.nextLocalIndex(i)) { require(i>last,"Mask iteration not increasing");last=i;count++; }
            require(count==4096&&s.size()==4096,"Dense mask lost blocks");
        }
        scan.clear();p=new Probe();
        Point edge=new Point(16,8,8),outside=new Point(17,8,8),negative=new Point(-1,-17,-1);
        p.add(edge);p.add(outside);p.add(negative);
        settle(scan,p,ORIGIN,1,8,-2,3,5);
        require(scan.snapshot().contains(edge)&&!scan.snapshot().contains(outside),"Manual range boundary is incorrect");
        require(contains(scan,outside),"Full section mask should retain just-outside-range data");
        scan.clear();
        Point far=new Point(560,319,560),below=new Point(-31,-63,-31);
        p=new Probe();p.restrictChunks=true;p.available.add(key(35,35));p.available.add(key(-2,-2));p.add(far);p.add(below);
        settle(scan,p,ORIGIN,35,0,-4,20,28);
        require(contains(scan,far),"Loaded-square distant corner/high world section excluded");
        require(contains(scan,below),"Full-height negative-coordinate section excluded");
        require(scan.snapshot().contains(far),"Loaded view accidentally applied a spherical range");
        for(SectionSnapshot s:scan.snapshotSections())require(s.section().y()<20,"Exclusive world top included");
        scan.clear();p=new Probe();p.add(new Point(511,8,8));p.restrictChunks=true;p.available.add(key(31,0));
        settle(scan,p,ORIGIN,32,512,0,1,20);
        require(contains(scan,new Point(511,8,8)),"Manual 512 range did not extend beyond 128");
    }
    static void scheduling() {
        BlockEspScan scan=new BlockEspScan();Probe p=new Probe();p.allPositive=true;p.indexed=true;
        tick(scan,p,ORIGIN,4,0,0,1);
        require(p.collections==16,"Indexed collection did not use its independent budget");
        p.indexed=false;
        tick(scan,p,ORIGIN,4,0,0,1);
        require(p.collections==2,"Changed block-entity index classification bypassed the full-walk budget");
        scan.clear();p=new Probe();p.allPositive=true;p.add(new Point(60,8,8));
        for(int i=0;i<100&&!contains(scan,new Point(60,8,8));i++)tick(scan,p,new Point((i&1)==0?15:16,8,8),4,0,0,1);
        require(contains(scan,new Point(60,8,8)),"Chunk seam movement starved distant work");
        scan.clear();p=new Probe();p.restrictChunks=true;
        Point delayed=new Point(500,300,500);p.add(delayed);
        tick(scan,p,ORIGIN,35,0,-4,20);
        p.available.add(key(31,31));scan.invalidateChunk(31,31);
        settle(scan,p,ORIGIN,35,0,-4,20,2);
        require(contains(scan,delayed),"Loaded chunk priority discovery waited for full large-radius pass");
        p.available.clear();scan.unloadChunk(31,31);
        p.available.add(key(31,31));
        settle(scan,p,ORIGIN,35,0,-4,20,20);
        require(contains(scan,delayed),"Cheap discovery did not recover a missed reload event");
        scan.clear();p=new Probe();
        for(int i=0;i<5;i++)tick(scan,p,ORIGIN,35,0,-2048,2048);
        require(p.revisions.size()<=5*BlockEspScan.DISCOVERY_BUDGET,"Extreme-height discovery enumerated entire volume eagerly");
        require(BlockEspScan.MAX_SECTION_STATES==131072,"Memory bound missing");
    }
    static void orderingAndImmutableUpdates() {
        BlockEspScan scan=new BlockEspScan();Probe p=new Probe();p.indexed=true;
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)p.add(new Point(x*16+8,8,z*16+8));
        settle(scan,p,ORIGIN,6,0,0,1,15);
        require(count(scan)==121,"Initial matching sections missing");
        List<SectionSnapshot> old=scan.snapshotSections();
        Point moved=new Point(20,8,18);
        settle(scan,p,moved,6,0,0,1,3);
        long previous=-1;
        Set<Section> unique=new HashSet<>();
        for(SectionSnapshot snapshot:scan.snapshotSections()) {
            long d=distance(snapshot.section(),moved);
            require(d>=previous,"Completed incremental ordering is not near-first");previous=d;
            require(unique.add(snapshot.section()),"Duplicate section in immutable tree");
        }
        require(old.size()==121,"Published old list changed size");
        for(int i=0;i<old.size();i++) require(old.get(i)!=null,"Immutable rank lookup failed");
        Random random=new Random(721);
        for(int i=0;i<40;i++) {
            int x=random.nextInt(7)-3,z=random.nextInt(7)-3;
            Section section=new Section(x,0,z);
            if((i&1)==0)p.points.remove(section);else p.add(new Point(x*16+8,8,z*16+8));
            scan.invalidate(section);
            tick(scan,p,moved,6,0,0,1);
            Set<Section> keys=new HashSet<>();
            for(SectionSnapshot snapshot:scan.snapshotSections()) require(keys.add(snapshot.section()),"Tree mutation duplicated key");
        }
        require(old.size()==121,"Immutable mask/index changed during AVL updates");
    }
    public static void main(String[] args) {
        steadyAndEvents();masksAndRanges();scheduling();orderingAndImmutableUpdates();
        System.out.println("Block ESP loaded-data index: "+checks+" assertions passed");
    }
}
