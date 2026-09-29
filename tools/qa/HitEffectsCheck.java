package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;

/** Deterministic event, lifetime, capacity and floor-path regressions; no Minecraft launch. */
public final class HitEffectsCheck {
    private static int assertions;
    public static void main(String[] args) {
        particles(); ground(); clocks();
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
        for(int seed=0;seed<96;seed++) {
            int[] reads={0};
            List<HitGroundPath.Branch> paths=HitGroundPath.build(.14,64,.31,seed,(x,z,y,r,d)-> {reads[0]++;return 64;});
            check(paths.size()==4,"four outward branches");
            check(reads[0]<=HitGroundPath.MAX_SURFACE_SAMPLES,"strict probe budget");
            for(var path:paths) {
                check(path.segments().size()==28,"seven one-block decisions");
                double previous=0;
                for(var edge:path.segments()) {
                    var a=edge.from(); var b=edge.to();
                    check(Math.abs(a.y()-64.018)<1e-7 && Math.abs(b.y()-64.018)<1e-7,"surface lift");
                    check(Math.abs(a.x()-Math.rint(a.x()))<1e-7 || Math.abs(a.z()-Math.rint(a.z()))<1e-7,"block seam");
                    check(Math.abs(Math.abs(b.x()-a.x())+Math.abs(b.z()-a.z())-.25)<1e-7,"cardinal quarter sample");
                    double radial=b.x()*b.x()+b.z()*b.z();
                    check(radial>previous,"continuously away, no backtracking"); previous=radial;
                    check(edge.end()>edge.start(),"growth time ordered");
                }
            }
        }
        var noFloor=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)->Double.NaN);
        check(noFloor.isEmpty(),"airborne target without nearby floor");
        var gap=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)->Math.abs(x)>.76?Double.NaN:64);
        for(var p:gap) for(var s:p.segments()) check(Math.abs(s.to().x())<=.76,"does not bridge missing floor");
        var stair=HitGroundPath.build(0,64,0,0,(x,z,y,r,d)-> {
            double top=64+Math.max(0,Math.floor(x*2))*.5;
            return top>y+r || top<y-d?Double.NaN:top;
        });
        boolean rise=false;
        for(var p:stair) for(var s:p.segments()) if(s.to().y()!=s.from().y()) {
            rise=true;check(s.to().x()==s.from().x() && s.to().z()==s.from().z(),"stair riser attached vertically");
        }
        check(rise,"slab and stair height supported");
        int[] pathological={0};
        HitGroundPath.build(0,0,0,0,(x,z,y,r,d)-> {pathological[0]++;return x==0&&z==0?0:Double.NaN;});
        check(pathological[0]<=256,"failed alternatives bounded");
        var flat=HitGroundPath.build(0,64,0,9,(x,z,y,r,d)->64);
        HitEffectsState state=new HitEffectsState();
        for(int i=0;i<10;i++) state.ground(flat,0,1,0x45CEEC);
        check(state.groundCount()==4,"independent ground capacity");
        check(state.frame(0).ground().isEmpty(),"ground starts empty");
        var early=state.frame(.07).ground();var grown=state.frame(.28).ground();
        check(!early.isEmpty() && grown.size()>early.size(),"progressive head movement");
        double earlyDistance=early.stream().mapToDouble(s->distance(s.to(),new HitEffectsState.Point(0,64.018,0))).max().orElse(0);
        double grownDistance=grown.stream().mapToDouble(s->distance(s.to(),new HitEffectsState.Point(0,64.018,0))).max().orElse(0);
        check(grownDistance>earlyDistance,"growth advances outward");
        state.clearGround(); check(state.frame(.3).isEmpty(),"ground toggle clears");
        state.ground(flat,0,1,0);check(state.frame(2).isEmpty(),"ground fades completely");
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
