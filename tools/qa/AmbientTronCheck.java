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
        paths(); lifecycle(); air(); edgeCells(); geometry(); joints(); clock();
        System.out.println("PASS ambient-tron " + checks + " production behavior assertions");
    }
    private static void paths() {
        int turns=0,bothAxes=0;Set<Integer> directions=new HashSet<>(),planes=new HashSet<>();
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
                    Set<Integer> axes=new HashSet<>();
                    var prev = segments.getFirst();
                    for (var s : segments) {
                        double[] a={s.ax(),s.ay(),s.az()},b={s.bx(),s.by(),s.bz()};
                        double dx=b[0]-a[0],dy=b[1]-a[1],dz=b[2]-a[2],length=Math.abs(dx)+Math.abs(dy)+Math.abs(dz);
                        check(Double.isFinite(s.ax()+s.ay()+s.az()+s.bx()+s.by()+s.bz()+s.width()+s.startOpacity()+s.endOpacity()), "finite path");
                        check(s.normalAxis()==prev.normalAxis() && a[s.normalAxis()]==b[s.normalAxis()], "fixed XY/XZ/YZ plane");
                        check((dx!=0?1:0)+(dy!=0?1:0)+(dz!=0?1:0)==1,"exactly one world axis");
                        int axis=dx!=0?0:dy!=0?1:2;
                        for(int coordinate=0;coordinate<3;coordinate++)if(coordinate!=axis)
                            check(a[coordinate]==Math.rint(a[coordinate]) && b[coordinate]==a[coordinate],"two exact integer block-grid coordinates");
                        axes.add(axis);directions.add(axis*2+((b[axis]-a[axis])>0?0:1));planes.add(s.normalAxis());
                        check(length > 0 && length <= 1.00000001, "one block maximum per edge");
                        check(s.startOpacity() >= 0 && s.endOpacity() >= 0 && s.endOpacity() <= 1, "finite alpha bounds");
                        check(s.endOpacity() >= s.startOpacity(), "fading tail towards bright head");
                        elevations.add(s.ay());
                        if(prev!=s && prev.bx()==s.ax() && prev.by()==s.ay() && prev.bz()==s.az()) {
                            check(s.ax()==Math.rint(s.ax()) && s.ay()==Math.rint(s.ay()) && s.az()==Math.rint(s.az()),"joints are integer world vertices");
                            double dot=(prev.bx()-prev.ax())*dx+(prev.by()-prev.ay())*dy+(prev.bz()-prev.az())*dz;
                            check(dot>=0,"no reversing or diagonal turns");if(dot==0)turns++;
                        }
                        prev = s;
                    }
                    check(axes.size()<=2,"trail remains two dimensional");if(axes.size()==2)bothAxes++;
                }
            }
        }
        check(turns>1000 && bothAxes>1000,"independent right-angle turns on each plane");
        check(directions.size()==6 && planes.size()==3,"positive and negative X/Y/Z travel across all planes");
        check(elevations.size()>30,"varied integer levels and interpolated vertical heads");
        for(double far:new double[]{-29999990,-17.75,29999990}) {
            var output=new AmbientTronField(79).sample(far,64,far,0,1,1,AIR);
            check(!output.isEmpty(),"negative/far-coordinate paths visible");
            for(var s:output) {
                double[] a={s.ax(),s.ay(),s.az()},b={s.bx(),s.by(),s.bz()};int fixed=0;
                for(int i=0;i<3;i++)if(a[i]==b[i]){fixed++;check(a[i]==Math.rint(a[i]),"far-coordinate grid remains exact");}
                check(fixed==2,"far-coordinate segment is axis aligned");
            }
        }
    }
    private static void lifecycle() {
        var field = new AmbientTronField(20);
        var first = field.sample(10,70,-10,0,.6,1,AIR);
        check(!first.isEmpty(), "enable produces visible trails");
        check(first.stream().allMatch(s->s.startOpacity()==0&&s.endOpacity()==0),"enabling starts fully transparent");
        check(first.equals(field.sample(10,70,-10,0,.6,1,AIR)), "paused time gives identical geometry");
        var moved = field.sample(10,70,-10,.02,.6,1,AIR);
        double earlyAlpha=moved.stream().mapToDouble(AmbientTronField.Segment::endOpacity).max().orElse(0);
        var fading=new AmbientTronField(20);fading.sample(10,70,-10,0,.6,1,AIR);
        double fullAlpha=fading.sample(10,70,-10,.65,.6,1,AIR).stream().mapToDouble(AmbientTronField.Segment::endOpacity).max().orElse(0);
        check(earlyAlpha>0&&fullAlpha>earlyAlpha*20,"initial field fades in gradually before full brightness");
        check(!first.equals(moved), "head interpolates between ticks");
        var groups0 = grouped(first); var groups1 = grouped(moved);
        int smooth = 0;
        for (var id : groups0.keySet()) if (groups1.containsKey(id)) {
            var a = groups0.get(id).getLast(); var b = groups1.get(id).getLast();
            double dist = Math.abs(a.bx()-b.bx())+Math.abs(a.by()-b.by())+Math.abs(a.bz()-b.bz());
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
        check(reads[0] <= AmbientTronField.MAX_TRAILS*4, "high FPS only checks newly exposed unit edges");
        clear[0]=false; reads[0]=0;
        check(field.sample(0,64,0,.051,1,1,air).isEmpty(), "new solid or fluid cells suppress trails on next 20 Hz slice");
        check(reads[0]<=AmbientTronField.MAX_AIR_QUERIES, "blocked probes bounded");
        clear[0]=true;
        check(!field.sample(0,64,0,.11,1,1,air).isEmpty(), "cleared air restores trail");
        reads[0]=0;
        field.sample(0,64,0,.2,0,1,air);
        check(reads[0]==0, "zero density performs no world reads");
    }
    private static void edgeCells() {
        for(int direction=0;direction<6;direction++)for(int start:new int[]{-30000000,-1,0,29999990}) {
            int[] a={start,64,start},b=a.clone();b[direction/2]+=((direction&1)==0?1:-1);
            Set<String> touched=new HashSet<>();int[] calls={0};
            boolean blocked=AmbientTronField.edgeVisible(a[0],a[1],a[2],b[0],b[1],b[2],direction,(x,y,z)->{calls[0]++;touched.add(x+","+y+","+z);return false;});
            check(!blocked && calls[0]==4 && touched.size()==4,"exactly four neighboring cells of an edge");
            int axis=direction/2,p=(axis+1)%3,q=(axis+2)%3;Set<String> expected=new HashSet<>();
            for(int i=-1;i<=0;i++)for(int j=-1;j<=0;j++){int[] cell=a.clone();cell[axis]=Math.min(a[axis],b[axis]);cell[p]+=i;cell[q]+=j;expected.add(cell[0]+","+cell[1]+","+cell[2]);}
            check(touched.equals(expected),"neighbor cells use mathematical floor at signed coordinates");
            for(String exposed:expected)check(AmbientTronField.edgeVisible(a[0],a[1],a[2],b[0],b[1],b[2],direction,(x,y,z)->exposed.equals(x+","+y+","+z)),"any exposed neighboring cell can show edge");
        }
    }
    private static void geometry() {
        var newborn=new AmbientTronField.Segment(1,0,0,0,.001,0,0,.11,.2f,.6f,.5);
        AmbientTronGeometry.beam(newborn,0,2,0,(x,y,z,u,v,a)->check(x>=-1E-12&&x<=.001+1E-12&&Math.abs(z)<=.00045+1E-12,"newborn caps cannot flash full-width bars"));
        for (var eye : new double[][]{{0,0,0},{.5,0,0},{0,2,0},{1E7,0,1E7},{0,0,2}}) {
            var s = new AmbientTronField.Segment(1,0,0,0,1,0,0,.11,.2f,.6f,.5);
            List<double[]> vertices = new ArrayList<>();
            AmbientTronGeometry.beam(s,eye[0],eye[1],eye[2],(x,y,z,u,v,a)->vertices.add(new double[]{x,y,z,u,v,a}));
            check(vertices.size()==AmbientTronGeometry.MAX_VERTICES, "bounded middle strip and two soft caps");
            for(var v:vertices) check(Double.isFinite(Arrays.stream(v).sum()), "edge-on beam finite");
            check(vertices.get(0)[3]==2 && vertices.get(1)[3]==2.5 && vertices.get(5)[3]==3, "split halves share a fixed bright-core centerline");
            check(vertices.get(0)[5]==0 && vertices.get(vertices.size()-1)[5]==0, "exposed tips must fade to zero");
            double[] a=vertices.get(8),b=vertices.get(13);
            for(var v:vertices)check(v[1]==0,"width stays in world plane, never faces camera");
            for(var v:vertices)check(v[0]>=0&&v[0]<=1,"terminal cap protrudes beyond endpoint");
            check(Math.abs(Math.sqrt((a[0]-b[0])*(a[0]-b[0])+(a[1]-b[1])*(a[1]-b[1])+(a[2]-b[2])*(a[2]-b[2]))-.22)<1E-8, "beam retains configured width");
            for(var v:vertices)if(v[3]==2.5)check(v[2]==0,"every core vertex stays exactly on final segment axis through pointed caps");
            for(var v:vertices)if(v[0]==0||v[0]==1)check(v[2]==0,"terminal endpoint is a single centered point");
        }
    }
    private static void joints() {
        for(int normal=0;normal<3;normal++)for(int first=0;first<6;first++)for(int next=0;next<6;next++) {
            if(first/2==normal || next/2==normal || (first^1)==next)continue;
            double[] mid={7,64,-12},a=mid.clone(),b=mid.clone();
            for(int i=0;i<3;i++){a[i]-=AmbientTronField.component(first,i);b[i]+=AmbientTronField.component(next,i);}
            var left=new AmbientTronField.Segment(1,a[0],a[1],a[2],mid[0],mid[1],mid[2],.11,.5f,.5f,.5,normal,-1,next);
            var right=new AmbientTronField.Segment(1,mid[0],mid[1],mid[2],b[0],b[1],b[2],.11,.5f,.5f,.5,normal,first,-1);
            List<double[]> l=new ArrayList<>(),r=new ArrayList<>(),moved=new ArrayList<>();
            AmbientTronGeometry.beam(left,0,0,0,(x,y,z,u,v,o)->l.add(new double[]{x,y,z}));
            AmbientTronGeometry.beam(right,0,0,0,(x,y,z,u,v,o)->r.add(new double[]{x,y,z}));
            AmbientTronGeometry.beam(left,300,15,-120,(x,y,z,u,v,o)->moved.add(new double[]{x,y,z}));
            for(int i=0;i<3;i++) {
                check(l.get(l.size()-2)[i]==r.get(5)[i] && l.get(l.size()-5)[i]==r.get(0)[i],"miter joints share identical edge positions");
                for(int v=0;v<l.size();v++)check(l.get(v)[i]==moved.get(v)[i],"camera motion cannot rotate flat geometry");
            }
            for(var v:l)check(v[normal]==mid[normal],"all corner vertices remain strictly planar");
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
