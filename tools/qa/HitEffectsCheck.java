package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Deterministic event, lifetime, capacity and floor-path regressions; no Minecraft launch. */
public final class HitEffectsCheck {
    private static int assertions;
    public static void main(String[] args) {
        particles(); ground(); polish(); clocks();
        System.out.println("PASS hit-effects-state: "+assertions+" behavior checks");
    }
    private static void particles() {
        HitEffectsState state=new HitEffectsState();
        check(state.accept(1,17),"first attack");
        check(!state.accept(1,17),"duplicate hook");
        check(state.accept(1,18),"different target same tick");
        check(state.accept(2,17),"next tick");
        HitEffectsState.Point origin=new HitEffectsState.Point(2,4,-3);
        state.burst(origin,0,12,1,0x45CEEC,HitEffectsState.Style.STARS,7);
        check(state.particleCount()==12,"configured amount");
        check(state.frame(0).particles().isEmpty(),"soft entrance starts transparent");
        var visible=state.frame(.08).particles();
        check(visible.size()==12,"particles visible");
        for(var p:visible) {
            check(p.style()==HitEffectsState.Style.STARS,"style snapshot");
            check(p.opacity()>.9 && p.opacity()<=1,"fade in");
            check(p.position().finite() && p.tail().finite(),"finite positions");
            check(p.radius()>0 && p.radius()<.1,"small sprite");
            check(distance(p.position(),p.tail())>0,"motion trail");
        }
        var later=state.frame(.5).particles();
        check(Math.abs(later.getFirst().rotation()-visible.getFirst().rotation())>.1,"rotation continues");
        check(distance(later.getFirst().position(),origin)>distance(visible.getFirst().position(),origin),"outward movement");
        for(int i=0;i<30;i++) state.burst(origin,.5,24,2,0,HitEffectsState.Style.DOTS,i);
        check(state.particleCount()==HitEffectsState.MAX_PARTICLES,"burst capacity");
        check(state.frame(3).isEmpty(),"expired particles removed");
        state.burst(new HitEffectsState.Point(Double.NaN,0,0),0,24,1,0,null,0);
        check(state.particleCount()==0,"invalid point rejected");
        state.burst(origin,0,Integer.MAX_VALUE,Double.NaN,0,null,0);
        check(state.particleCount()==24,"settings clamped");
        state.clearParticles(); check(state.particleCount()==0,"particle toggle clears");
        HitEffectsState.Particle particle=new HitEffectsState.Particle(origin,new HitEffectsState.Point(1,1,0),0,2,.1,0,1,0,HitEffectsState.Style.SPARKS);
        check(HitEffectsState.position(particle,.1).y()>origin.y(),"initial upward motion");
        check(HitEffectsState.position(particle,1.5).y()<origin.y(),"gentle falling motion");
        check(HitEffectsState.opacity(0,1)==0 && HitEffectsState.opacity(1,1)==0,"lifetime boundaries");
        state.clear(); check(state.accept(2,17),"reset clears duplicate guard");
    }
    private static void ground() {
        for(int seed=0;seed<512;seed++) {
            int[] reads={0};
            List<HitGroundPath.Branch> paths=HitGroundPath.build(.14,64,.31,seed,(x,z,y,r,d)-> {reads[0]++;return 64;});
            check(paths.size()==9,"nine outward branches on open floor: "+seed);
            check(new HashSet<>(paths).size()==9,"all nine routes distinct");
            check(reads[0]<=HitGroundPath.MAX_SURFACE_SAMPLES,"strict probe budget");
            Set<String> sharedStarts=new HashSet<>(),secondSteps=new HashSet<>();
            for(var path:paths) {
                check(path.segments().size()==HitGroundPath.STEPS,"ten one-block decisions");
                sharedStarts.add(edgeKey(path.segments().getFirst().from(),path.segments().getFirst().to()));
                secondSteps.add(edgeKey(path.segments().get(1).from(),path.segments().get(1).to()));
                Set<String> ownEdges=new HashSet<>();
                for(var edge:path.segments()) {
                    var a=edge.from(); var b=edge.to();
                    check(Math.abs(a.y()-64.018)<1e-7 && Math.abs(b.y()-64.018)<1e-7,"surface lift");
                    check(integer(a.x()) && integer(a.z()) && integer(b.x()) && integer(b.z()),"all endpoints at integer seams");
                    check(Math.abs(Math.abs(b.x()-a.x())+Math.abs(b.z()-a.z())-1)<1e-7,"exactly one cardinal block");
                    check(a.y()==b.y(),"strict flat 2D");
                    check(ownEdges.add(edgeKey(a,b)),"a trail never retraces its own undirected edge");
                    check(edge.end()>edge.start(),"growth time ordered");
                }
                crossings(path);
            }
            check(sharedStarts.size()==4,"four shared initial exits");
            check(secondSteps.size()>=4,"routes fan beyond the four initial exits");
            // Emulate exactly which block columns the real seam sampler touches on a full-block floor.
            Set<String> columns=new HashSet<>();
            for(var path:paths)for(var edge:path.segments())for(int sample=0;sample<=4;sample++)for(int side=0;side<4;side++) {
                double t=sample*.25,x=edge.from().x()+(edge.to().x()-edge.from().x())*t,z=edge.from().z()+(edge.to().z()-edge.from().z())*t;
                columns.add((int)Math.floor(x+((side&1)==0?-.012:.012))+","+(int)Math.floor(z+((side&2)==0?-.012:.012)));
            }
            check(columns.size()*3+16<=640,"actual flat collision read budget supports all nine routes");
        }
        var noFloor=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)->Double.NaN);
        check(noFloor.isEmpty(),"airborne target without nearby floor");
        var gap=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)->Math.abs(x)>.76?Double.NaN:64);
        for(var p:gap) for(var s:p.segments()) check(Math.abs(s.to().x())<=.76,"does not bridge missing floor");
        var stair=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)-> {
            double top=64+(x>=.5?.5:0);
            return top>y+r || top<y-d?Double.NaN:top;
        });
        for(var p:stair) for(var s:p.segments()) {
            check(s.to().y()==s.from().y() && s.from().y()==64.018,"height changes never get risers");
            check(s.to().x()<.5 && s.from().x()<.5,"path stops before stair transition");
        }
        var slab=HitGroundPath.build(.2,64.5,.2,19,(x,z,y,r,d)->64.5);
        check(slab.size()==9,"flat slab floor supported");
        for(var p:slab)for(var s:p.segments())check(s.from().y()==64.518 && s.to().y()==64.518,"slab anchored flat");
        int[] pathological={0};
        HitGroundPath.build(0,0,0,0,(x,z,y,r,d)-> {pathological[0]++;return x==0&&z==0?0:Double.NaN;});
        check(pathological[0]<=HitGroundPath.MAX_SURFACE_SAMPLES,"failed alternatives bounded");
        var flat=HitGroundPath.build(0,64,0,9,(x,z,y,r,d)->64);
        var compatible=new HitEffectsState.GroundPulse(flat,0,1,0x2244AA);
        check(compatible.color()==compatible.endColor(),"existing ground pulse constructor retained");
        HitEffectsState state=new HitEffectsState();
        for(int i=0;i<10;i++) state.ground(flat,0,1,0x2244AA,0x66EEFF);
        check(state.groundCount()==4,"independent ground capacity");
        check(state.frame(0).ground().isEmpty(),"ground starts empty");
        var early=state.frame(.07).ground();var grown=state.frame(.28).ground();
        check(!early.isEmpty() && grown.size()>early.size(),"progressive head movement");
        check(early.size()==4,"shared prefixes drawn only once even with four simultaneous pulses");
        for(var frame:List.of(early,grown,state.frame(.5).ground(),state.frame(.8).ground(),state.frame(1.1).ground())) {
            Set<String> edges=new HashSet<>();
            for(var beam:frame) {
                check(edges.add(edgeKey(beam.from(),beam.to())),"no repeated edge overdraw");
                check(integer(beam.from().x())&&integer(beam.from().z())&&integer(beam.to().x())&&integer(beam.to().z()),"whole block fade keeps grid endpoints");
                check(Math.abs(Math.abs(beam.to().x()-beam.from().x())+Math.abs(beam.to().z()-beam.from().z())-1)<1e-8,"fade never shortens segment");
                check(beam.color()!=beam.endColor(),"gradient has both endpoints");
            }
        }
        HitEffectsState fade=new HitEffectsState();fade.ground(flat,0,1,0x2244AA,0x66EEFF);
        var enter=fade.frame(.015).ground().getFirst();var full=fade.frame(.075).ground().getFirst();
        check(enter.from().equals(full.from()) && enter.to().equals(full.to()) && enter.opacity()<full.opacity(),"block fades in without growing");
        var leaving=fade.frame(.75).ground().stream().filter(b->b.from().equals(enter.from())&&b.to().equals(enter.to())).findFirst().orElseThrow();
        check(leaving.opacity()<full.opacity() && leaving.to().equals(full.to()),"block fades out without shrinking");
        double earlyDistance=early.stream().mapToDouble(s->distance(s.to(),new HitEffectsState.Point(0,64.018,0))).max().orElse(0);
        double grownDistance=grown.stream().mapToDouble(s->distance(s.to(),new HitEffectsState.Point(0,64.018,0))).max().orElse(0);
        check(grownDistance>earlyDistance,"growth advances outward");
        state.clearGround(); check(state.frame(.3).isEmpty(),"ground toggle clears");
        state.ground(flat,0,1,0);check(state.frame(2.3).isEmpty(),"ground fades completely");
        palette();
    }
    private static void polish() {
        int maxColumns=0,maxReads=0,backwardRoutes=0,crossingRoutes=0,sharedPulseEdges=0;int[] ninthHeadings=new int[8],crossingHeadings=new int[8];
        Set<String> variety=new HashSet<>();
        for(int seed=0;seed<2048;seed++) {
            int[] reads={0};
            var paths=HitGroundPath.build(-11.2,63,-8.8,seed,(x,z,y,r,d)->{reads[0]++;return 63;});
            maxReads=Math.max(maxReads,reads[0]);
            Set<String> columns=new HashSet<>();int[] headings=new int[8];
            Set<String> pulseEdges=new HashSet<>();
            for(var route:paths) {
                var first=route.segments().getFirst();var end=route.segments().getLast();
                double ex=end.to().x()-first.from().x(),ez=end.to().z()-first.from().z();
                int heading=compass(ex,ez);headings[heading]++;
                irregular(route,heading);
                int[] hx={1,1,0,-1,-1,-1,0,1},hz={0,1,1,1,0,-1,-1,-1};
                double forward=ex*hx[heading]+ez*hz[heading],cross=Math.abs(ex*hz[heading]-ez*hx[heading]);
                check(forward>=((heading&1)==0?4:6)&&cross<=forward*.414213562373095,"final heading stays in one compass cone");
                int backwards=0;
                Set<String> ownEdges=new HashSet<>();
                for(var edge:route.segments()) {
                    check(ownEdges.add(edgeKey(edge.from(),edge.to())),"backward hiccup never reuses its own edge in either direction");
                    double advance=(edge.to().x()-edge.from().x())*hx[heading]+(edge.to().z()-edge.from().z())*hz[heading];
                    if(advance<0)backwards++;
                    if(!pulseEdges.add(edgeKey(edge.from(),edge.to())))sharedPulseEdges++;
                }
                check(backwards<=2,"backward detour is short and bounded to two unit edges");
                if(backwards>0)backwardRoutes++;
                if(crossings(route)>0){crossingRoutes++;crossingHeadings[heading]++;}
                for(var edge:route.segments())for(int i=0;i<=4;i++)for(int side=0;side<4;side++) {
                    var p=edge.from().lerp(edge.to(),i*.25);
                    columns.add((int)Math.floor(p.x()+((side&1)==0?-.012:.012))+","+(int)Math.floor(p.z()+((side&2)==0?-.012:.012)));
                }
            }
            maxColumns=Math.max(maxColumns,columns.size());
            check(paths.size()==9,"nine compass paths preserved");
            for(int heading=0;heading<8;heading++) {
                check(headings[heading]>=1&&headings[heading]<=2,"all eight compass headings covered");
                if(headings[heading]==2)ninthHeadings[heading]++;
            }
            if(seed<64)variety.add(paths.toString());
            check(reads[0]<=384,"extended search fixed probe budget");
            check(columns.size()*3+16<=640,"real cached floor reads fit bound");
        }
        check(backwardRoutes>200&&backwardRoutes<2048*9/2,"occasional real backward steps occur across seeds");
        check(crossingRoutes>200&&crossingRoutes<2048*9/3,"occasional actual seeded routes cross themselves perpendicularly");
        for(int count:crossingHeadings)check(count>20,"perpendicular self-crossing occurs in every compass heading");
        check(sharedPulseEdges>1000,"separate trails may share edges and remain independently planned");
        for(int count:ninthHeadings)check(count>100,"randomized ninth path covers every heading across seeds");
        check(variety.size()>=60,"seeded route shapes vary");
        for(double[] origin:new double[][]{{-23.2,-31.8},{0.2,-.2},{29999990.2,-29999990.2}}) {
            var local=HitGroundPath.build(.2,.0,-.2,819273,(x,z,y,r,d)->0);
            var shifted=HitGroundPath.build(origin[0],0,origin[1],819273,(x,z,y,r,d)->0);
            check(shifted.equals(HitGroundPath.build(origin[0],0,origin[1],819273,(x,z,y,r,d)->0)),"seeded route is deterministic");
            check(shifted.size()==9,"far and negative coordinates keep all headings");
            for(int i=0;i<9;i++)for(int step=0;step<HitGroundPath.STEPS;step++) {
                var a=local.get(i).segments().get(step);var b=shifted.get(i).segments().get(step);
                check(b.from().x()-Math.rint(origin[0])==a.from().x()&&b.from().z()-Math.rint(origin[1])==a.from().z(),"path translation preserves snapped grid geometry");
                check(b.to().finite()&&integer(b.to().x())&&integer(b.to().z()),"far endpoints finite and integral");
            }
        }
        System.out.println("Ground compass measured bounds: surface="+maxReads+", columns="+maxColumns+", blockStates="+(maxColumns*3+16)+", backwardRoutes="+backwardRoutes+", crossingRoutes="+crossingRoutes+", sharedPulseEdges="+sharedPulseEdges);
        var paths=HitGroundPath.build(0,0,0,91,(x,z,y,r,d)->0);
        var repeated=new HitEffectsState();repeated.ground(paths,0,1,0x2244AA,0x66EEFF);
        check(repeated.frame(.3).ground().stream().allMatch(b->b.highlight()==0),"one hit shared branches do not fake a repeat pulse");
        repeated.ground(paths,.45,1,0x2244AA,0x66EEFF);
        var flash=repeated.frame(.515).ground();
        check(flash.stream().anyMatch(b->b.highlight()>.8),"repeated hit creates strong short highlight");
        double early=flash.stream().filter(b->b.highlight()>.5).mapToDouble(b->distance(b.from(),paths.getFirst().segments().getFirst().from())).average().orElse(-1);
        double later=repeated.frame(.74).ground().stream().filter(b->b.highlight()>.5).mapToDouble(b->distance(b.from(),paths.getFirst().segments().getFirst().from())).average().orElse(-1);
        check(later>early+1,"highlight wave travels outward across block edges");
        check(repeated.frame(.85).ground().stream().filter(b->b.from().equals(paths.getFirst().segments().getFirst().from())).allMatch(b->b.highlight()==0),"origin highlight ends instead of staying bright");
        var oldConstructor=new HitEffectsState.Beam(new HitEffectsState.Point(0,0,0),new HitEffectsState.Point(1,0,0),.05,1,0x2288AA,0x66DDEE);
        check(oldConstructor.highlight()==0,"six-argument Beam constructor retained");
        var reverse=new HitEffectsState();
        reverse.ground(List.of(new HitGroundPath.Branch(List.of(new HitGroundPath.Segment(oldConstructor.from(),oldConstructor.to(),0,1)))),0,1,0x2288AA);
        reverse.ground(List.of(new HitGroundPath.Branch(List.of(new HitGroundPath.Segment(oldConstructor.to(),oldConstructor.from(),0,1)))),.45,1,0x2288AA);
        var reversed=reverse.frame(.5).ground().getFirst();
        double left=reversed.from().equals(oldConstructor.from())?reversed.highlight():reversed.endHighlight();
        double right=reversed.from().equals(oldConstructor.to())?reversed.highlight():reversed.endHighlight();
        check(right>.9&&left==0,"new wave follows latest hit direction when overlap traverses edge in reverse");
        var vertices=new java.util.ArrayList<double[]>();
        HitGroundGeometry.emit(List.of(oldConstructor),(x,y,z,u,v,a,c)->vertices.add(new double[]{x,y,z,u,v,a,c}));
        check(vertices.size()==56,"isolated strip has two tapered end sections split on its centerline");
        for(var v:vertices) {
            check(v[0]>=0&&v[0]<=1&&v[1]==0,"no cap extends beyond integer grid endpoints or ground plane");
            check(Double.isFinite(v[2])&&v[5]>=0&&v[5]<=1,"finite bounded mesh");
            if(v[0]==0||v[0]==1)check(v[2]==0&&v[5]==0,"tip pinches and fades to zero with no flat bright cap");
        }
        var bright=new HitEffectsState.Beam(oldConstructor.from(),oldConstructor.to(),.05,1,oldConstructor.color(),oldConstructor.endColor(),1);
        var highlighted=new java.util.ArrayList<double[]>();
        HitGroundGeometry.emit(List.of(bright),(x,y,z,u,v,a,c)->highlighted.add(new double[]{x,z,a,c}));
        check(highlighted.stream().mapToDouble(v->Math.abs(v[1])).max().orElse(0)>.075,"overlap wave visibly widens the ribbon");
        check(highlighted.stream().anyMatch(v->((int)v[3]>>16&255)>150),"overlap wave visibly brightens the palette");
        var elbow=new HitEffectsState.Beam(oldConstructor.to(),new HitEffectsState.Point(1,0,1),.05,1,0x66DDEE);
        vertices.clear();HitGroundGeometry.emit(List.of(oldConstructor,elbow),(x,y,z,u,v,a,c)->vertices.add(new double[]{x,y,z,u,v,a,c}));
        check(vertices.stream().filter(v->v[5]==1&&Math.abs(v[0]-.95)<1e-8&&Math.abs(v[2]-.05)<1e-8).count()==2,"inside right-angle miter joins exactly");
        check(vertices.stream().filter(v->v[5]==1&&Math.abs(v[0]-1.05)<1e-8&&Math.abs(v[2]+.05)<1e-8).count()==2,"outside right-angle miter joins exactly");
        repeated.clearGround();check(repeated.frame(.9).isEmpty(),"toggle clears repeat-wave state too");
    }
    private static int compass(double x,double z) {return Math.floorMod((int)Math.round(Math.atan2(z,x)/(Math.PI/4)),8);}
    private static void irregular(HitGroundPath.Branch route,int heading) {
        var edges=route.segments();int turns=0,adjacentStraight=0;
        for(int i=1;i<edges.size();i++) {
            var a=edges.get(i-1);var b=edges.get(i);
            double dot=(a.to().x()-a.from().x())*(b.to().x()-b.from().x())
                +(a.to().z()-a.from().z())*(b.to().z()-b.from().z());
            if(dot==0)turns++;else {check(dot==1,"bend never instantly doubles back");adjacentStraight++;}
        }
        check(turns>=4,"every route contains several interior right-angle bends");
        for(int first=0;first+3<edges.size();first++) {
            var a=edges.get(first).from();var b=edges.get(first+3).to();
            check(Math.max(Math.abs(b.x()-a.x()),Math.abs(b.z()-a.z()))<4,"no four-block laser-straight stretch");
        }
        if((heading&1)==1)check(adjacentStraight>0,"diagonal stair steps vary instead of mechanically alternating every block");
        else {
            var origin=edges.getFirst().from();boolean xHeading=(heading%4)==0;
            int lateral=0;boolean positive=false,negative=false,interiorExcursion=false;
            for(int i=0;i<edges.size();i++) {
                var edge=edges.get(i);double delta=xHeading?edge.to().z()-edge.from().z():edge.to().x()-edge.from().x();
                if(delta!=0){lateral++;positive|=delta>0;negative|=delta<0;}
                double deviation=xHeading?edge.to().z()-origin.z():edge.to().x()-origin.x();
                if(i>=1&&i<=6&&Math.abs(deviation)>=1)interiorExcursion=true;
            }
            check(lateral>=3&&positive&&negative,"cardinal path makes several side steps and returns toward its heading");
            check(interiorExcursion,"sideways deviation happens in the route body, not only its endpoint");
        }
    }
    private static int crossings(HitGroundPath.Branch route) {
        var edges=route.segments();var visits=new java.util.HashMap<HitEffectsState.Point,Integer>();
        visits.put(edges.getFirst().from(),0);int count=0;
        for(int vertex=1;vertex<=edges.size();vertex++) {
            var point=edges.get(vertex-1).to();Integer previous=visits.putIfAbsent(point,vertex);
            if(previous==null)continue;
            check(previous>0&&previous<edges.size()&&vertex<edges.size(),"a crossing is interior on both passes");
            var before=edges.get(previous-1);var after=edges.get(previous);var enter=edges.get(vertex-1);var leave=edges.get(vertex);
            double ax=before.to().x()-before.from().x(),az=before.to().z()-before.from().z();
            double bx=enter.to().x()-enter.from().x(),bz=enter.to().z()-enter.from().z();
            check(ax==after.to().x()-after.from().x()&&az==after.to().z()-after.from().z(),"original pass goes straight through crossing");
            check(bx==leave.to().x()-leave.from().x()&&bz==leave.to().z()-leave.from().z(),"return pass continues straight across crossing");
            check(ax*bx+az*bz==0,"self-crossing passes are perpendicular, never overlapping");
            count++;
        }
        check(count<=1,"at most one short crossing detour in a ten-edge route");
        return count;
    }
    private static void palette() {
        for(var preset:VisualAtmosphere.Preset.values()) {
            VisualAtmosphere.configure(true,preset.sky(),false);
            int start=VisualAtmosphere.tint(0x4BD2FF,0,.75),end=VisualAtmosphere.tint(0x7799BB,0,.25);
            check(start==preset.primary() && end==preset.accent(),"actual atmosphere gradient endpoints "+preset);
            check(HitEffectsState.gradient(start,end,0)==start && HitEffectsState.gradient(start,end,1)==end,"gradient preserves palette endpoints");
            for(int shift=0;shift<=16;shift+=8) {
                int a=start>>shift&255,b=end>>shift&255,m=HitEffectsState.gradient(start,end,.5)>>shift&255;
                check(m>=Math.min(a,b)&&m<=Math.max(a,b),"gradient stays inside palette");
            }
        }
        VisualAtmosphere.configure(true,"Sunset",true);
        int manual=0x24689A,light=HitEffectsState.gradient(manual,0xFFFFFF,.22);
        check(VisualAtmosphere.tint(manual,0,.75)==manual && VisualAtmosphere.tint(light,0,.25)==light,"advanced mode honors manual gradient");
        VisualAtmosphere.clear();check(VisualAtmosphere.tint(manual,0,.75)==manual,"disabled atmosphere honors manual color");
    }
    private static boolean integer(double x){return Math.abs(x-Math.rint(x))<1e-8;}
    private static String edgeKey(HitEffectsState.Point a,HitEffectsState.Point b) {
        String first=a.toString(),second=b.toString();return first.compareTo(second)<=0?first+"/"+second:second+"/"+first;
    }
    private static void clocks() {
        var clock=new HitEffectsState.Clock();
        check(clock.sample(1_000_000_000L,false)==0,"initial clock");
        check(Math.abs(clock.sample(1_100_000_000L,false)-.1)<1e-8,"elapsed time");
        check(Math.abs(clock.sample(5_000_000_000L,true)-.1)<1e-8,"paused time frozen");
        check(Math.abs(clock.sample(5_100_000_000L,false)-.2)<1e-8,"unpause no jump");
        check(clock.sample(6_000_000_000L,false)<.46,"hitch bounded");
        clock.clear();check(clock.sample(8_000_000_000L,false)==0,"world reset clock");
    }
    private static double distance(HitEffectsState.Point a,HitEffectsState.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
    private static void check(boolean pass,String name) {
        assertions++;if(!pass)throw new AssertionError(name);
    }
}
