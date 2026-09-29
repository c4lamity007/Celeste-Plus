package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.*;

/** Runs production path generation, movement, air caching, lifetime and beam geometry. */
public final class AmbientTronCheck {
    private static int checks;
    private static final AmbientTronField.AirCell AIR = (x,y,z) -> true;
    private static void check(boolean b, String message) { checks++; if (!b) throw new AssertionError(message); }
    private static Map<Long,List<AmbientTronField.Segment>> grouped(List<AmbientTronField.Segment> input) {
        Map<Long,List<AmbientTronField.Segment>> out = new LinkedHashMap<>();
        for (var s : input) out.computeIfAbsent(s.trail(), ignored -> new ArrayList<>()).add(s);
        return out;
    }
    public static void main(String[] args) {
        paths(); lifecycle(); air(); geometry(); clock();
        System.out.println("PASS ambient-tron " + checks + " production behavior assertions");
    }
    private static void paths() {
        int turns = 0, bothAxes = 0;
        Set<Double> elevations = new HashSet<>();
        for (int seed = 1; seed <= 24; seed++) {
            var field = new AmbientTronField(seed);
            for (int frame = 0; frame < 48; frame++) {
                double time = frame * .21;
                var out = field.sample(0,64,0,time,1,1,AIR);
                check(out.size() <= AmbientTronField.MAX_TRAILS * AmbientTronField.MAX_SEGMENTS_PER_TRAIL, "bounded geometry");
                Map<Long,List<AmbientTronField.Segment>> groups = grouped(out);
                check(groups.size() <= AmbientTronField.MAX_TRAILS, "bounded trails");
                for (var segments : groups.values()) {
                    check(segments.size() <= AmbientTronField.MAX_SEGMENTS_PER_TRAIL, "bounded trail length");
                    boolean xAxis = false, zAxis = false;
                    var prev = segments.getFirst();
                    for (var s : segments) {
                        double dx = s.bx()-s.ax(), dz = s.bz()-s.az(), length = Math.abs(dx)+Math.abs(dz);
                        check(Double.isFinite(s.ax()+s.ay()+s.az()+s.bx()+s.by()+s.bz()+s.width()+s.startOpacity()+s.endOpacity()), "finite path");
                        check(s.ay() == s.by() && s.ay() == prev.ay(), "constant planar elevation");
                        check((dx == 0) != (dz == 0), "one orthogonal axis");
                        check(length > 0 && length <= 1.00000001, "one block maximum per edge");
                        check(s.startOpacity() >= 0 && s.endOpacity() >= 0 && s.endOpacity() <= 1, "finite alpha bounds");
                        check(s.endOpacity() >= s.startOpacity(), "fading tail towards bright head");
                        elevations.add(s.ay());
                        if (dx != 0) xAxis = true; else zAxis = true;
                        if (prev.bx() == s.ax() && prev.bz() == s.az()
                            && ((prev.bx()-prev.ax() == 0) != (dx == 0))) turns++;
                        prev = s;
                    }
                    if (xAxis && zAxis) bothAxes++;
                }
            }
        }
        check(turns > 1000 && bothAxes > 1000, "many independent right-angle turns on both axes");
        check(elevations.size() > 200, "varied vertical spawn levels");
    }
    private static void lifecycle() {
        var field = new AmbientTronField(20);
        var first = field.sample(10,70,-10,0,.6,1,AIR);
        check(!first.isEmpty(), "enable produces visible trails");
        check(first.equals(field.sample(10,70,-10,0,.6,1,AIR)), "paused time gives identical geometry");
        var moved = field.sample(10,70,-10,.02,.6,1,AIR);
        check(!first.equals(moved), "head interpolates between ticks");
        var groups0 = grouped(first); var groups1 = grouped(moved);
        int smooth = 0;
        for (var id : groups0.keySet()) if (groups1.containsKey(id)) {
            var a = groups0.get(id).getLast(); var b = groups1.get(id).getLast();
            double dist = Math.abs(a.bx()-b.bx())+Math.abs(a.bz()-b.bz());
            if (dist > 0 && dist <= .0620001) smooth++;
        }
        check(smooth >= 3, "continuous head motion at varied speed");
        var more = new AmbientTronField(20).sample(10,70,-10,0,1,1,AIR);
        check(grouped(first).size() <= Math.ceil(.6*AmbientTronField.MAX_TRAILS), "density controls trail count");
        check(grouped(more).size() > grouped(first).size(), "increasing density adds trails");
        check(field.sample(10,70,-10,.03,0,1,AIR).isEmpty(), "zero density emits nothing");
        var small = new AmbientTronField(20).sample(10,70,-10,0,.6,.5,AIR);
        var large = new AmbientTronField(20).sample(10,70,-10,0,.6,2,AIR);
        check(small.size()==large.size(), "size preserves paths");
        for (int i=0;i<small.size();i++) {
            check(small.get(i).ax()==large.get(i).ax() && small.get(i).bx()==large.get(i).bx(), "size leaves one-block grid unchanged");
            check(large.get(i).width()==small.get(i).width()*4, "size adjusts glow width");
        }
        field.reset();
        check(first.equals(field.sample(10,70,-10,0,.6,1,AIR)), "reset clears previous field state");
        var later = field.sample(10,70,-10,20,.6,1,AIR);
        check(Collections.disjoint(grouped(first).keySet(),grouped(later).keySet()), "completed lifetimes replace old trail identities");
        // Fresh births start transparent, then fade in.
        check(later.isEmpty(), "replacement birth opacity is zero");
        check(!field.sample(10,70,-10,20.8,.6,1,AIR).isEmpty(), "replacement trails grow and fade in");
        var teleported = field.sample(12000,80,-500,20.8,.6,1,AIR);
        check(!teleported.isEmpty(), "teleport reseeds local field");
        for (var s:teleported) check(Math.abs(s.ax()-12000)<30 && Math.abs(s.az()+500)<30, "teleport leaves no old geometry");
        for (double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            check(field.sample(bad,64,0,0,1,1,AIR).isEmpty(), "invalid camera rejected");
            check(field.sample(0,64,0,bad,1,1,AIR).isEmpty(), "invalid clock rejected");
            var fallback = new AmbientTronField(3).sample(0,64,0,0,bad,bad,AIR);
            for(var s:fallback) check(Double.isFinite(s.width()+s.endOpacity()), "invalid settings get finite fallback");
        }
    }
    private static void air() {
        var field = new AmbientTronField(8);
        int[] reads={0}; boolean[] clear={true};
        AmbientTronField.AirCell air=(x,y,z)->{reads[0]++;return clear[0];};
        var initial = field.sample(0,64,0,0,1,1,air);
        check(reads[0]>0 && reads[0]<=AmbientTronField.MAX_AIR_QUERIES, "bounded initial air probes");
        reads[0]=0;
        check(initial.equals(field.sample(0,64,0,0,1,1,air)), "cached frame matches");
        check(reads[0]==0, "no repeat world queries while paused");
        field.sample(0,64,0,.001,1,1,air);
        check(reads[0] <= AmbientTronField.MAX_TRAILS*2, "high FPS only checks newly exposed unit edges");
        clear[0]=false; reads[0]=0;
        check(field.sample(0,64,0,.051,1,1,air).isEmpty(), "new solid or fluid cells suppress trails on next 20 Hz slice");
        check(reads[0]<=AmbientTronField.MAX_AIR_QUERIES, "blocked probes bounded");
        clear[0]=true;
        check(!field.sample(0,64,0,.11,1,1,air).isEmpty(), "cleared air restores trail");
        reads[0]=0;
        field.sample(0,64,0,.2,0,1,air);
        check(reads[0]==0, "zero density performs no world reads");
    }
    private static void geometry() {
        for (var eye : new double[][]{{0,0,0},{.5,0,0},{0,2,0},{1E7,0,1E7},{0,0,2}}) {
            var s = new AmbientTronField.Segment(1,0,0,0,1,0,0,.11,.2f,.6f,.5);
            List<double[]> vertices = new ArrayList<>();
            AmbientTronGeometry.beam(s,eye[0],eye[1],eye[2],(x,y,z,u,v,a)->vertices.add(new double[]{x,y,z,u,v,a}));
            check(vertices.size()==4, "beam uses one quad");
            for(var v:vertices) check(Double.isFinite(Arrays.stream(v).sum()), "edge-on beam finite");
            check(vertices.get(0)[3]==2 && vertices.get(1)[3]==3, "uses production bright core and glow shader branch");
            check(vertices.get(0)[5]==.2f && vertices.get(3)[5]==.6f, "interpolated tail alpha reaches vertices");
            double[] a=vertices.get(0),b=vertices.get(1);
            check(Math.abs(Math.sqrt((a[0]-b[0])*(a[0]-b[0])+(a[1]-b[1])*(a[1]-b[1])+(a[2]-b[2])*(a[2]-b[2]))-.22)<1E-8, "beam retains configured width");
        }
    }
    private static void clock() {
        var clock = new JumpCircleAnimation.Clock();
        check(clock.sample(1_000_000_000L,false)==0, "clock begins at zero");
        check(clock.sample(2_000_000_000L,false)==1, "clock advances");
        check(clock.sample(3_000_000_000L,true)==1 && clock.sample(30_000_000_000L,true)==1, "pause freezes animation");
        check(clock.sample(31_000_000_000L,false)==1, "resume excludes paused duration");
        check(clock.sample(32_000_000_000L,false)==2, "resume continues normal motion");
        clock.reset(); check(clock.sample(34_000_000_000L,false)==0, "reset clears clock");
    }
}
