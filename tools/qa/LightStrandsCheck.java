package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;

/** Deterministic lifecycle, density and query-budget regression checks for actual strand sampling. */
public final class LightStrandsCheck {
    private static int checks;
    private static void check(boolean condition,String label) { checks++;if(!condition)throw new AssertionError(label); }
    private static double distance(AmbientLightStrands.Point a,AmbientLightStrands.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
    public static void main(String[] args) {
        check(AmbientLightStrands.strandCount(1)==72,"100% retains tripled density");
        check(AmbientLightStrands.strandCount(1.0/3)==24,"Density scales predictably");
        check(AmbientLightStrands.strandCount(Double.NaN)==0,"Nonfinite density is empty");
        int[] reads={0};
        var full=AmbientLightStrands.sample(0,64,0,12,1,(x,y,z)->{reads[0]++;return true;},(x,y,z,r)->true);
        check(full.size()>=60&&full.size()<=72,"Open scene loses population outside the cap fade");
        check(reads[0]<=72*12,"No unbounded terrain queries");
        check(full.equals(AmbientLightStrands.sample(0,64,0,12,1,(x,y,z)->true,(x,y,z,r)->true)),"Repeated paused frame changes strands");
        check(!full.equals(AmbientLightStrands.sample(0,64,0,12.1,1,(x,y,z)->true,(x,y,z,r)->true)),"Motion stopped");
        check(AmbientLightStrands.sample(0,64,0,12,0,(x,y,z)->{throw new AssertionError("Zero-density world query");},(x,y,z,r)->true).isEmpty(),"Zero density retains tails");
        check(AmbientLightStrands.sample(0,64,0,12,1,(x,y,z)->{throw new AssertionError("Offscreen world query");},(x,y,z,r)->false).isEmpty(),"Frustum rejection retains geometry");
        check(AmbientLightStrands.sample(0,64,0,12,1,(x,y,z)->false,(x,y,z,r)->true).isEmpty(),"Strands enter solid terrain");
        check(AmbientLightStrands.sample(Double.NaN,64,0,12,1,(x,y,z)->true,(x,y,z,r)->true).isEmpty(),"NaN camera accepted");
        for(int frame=0;frame<150;frame++) {
            double time=frame*.27;
            var sampled=AmbientLightStrands.sample(0,64,0,time,1,(x,y,z)->true,(x,y,z,r)->true);
            check(sampled.size()<=72,"Population accumulated");
            for(var strand:sampled) {
                check(strand.points().size()==12,"Tail history grew");
                check(strand.opacity()>0&&strand.opacity()<=1,"Invalid fade envelope");
                double length=0;
                for(int i=1;i<strand.points().size();i++) {
                    double d=distance(strand.points().get(i-1),strand.points().get(i));
                    check(Double.isFinite(d)&&d<.32,"Tail connects across recycle/teleport");length+=d;
                }
                check(length<2.6,"Tail exceeds culling bound");
                check(strand.points().getFirst().y()<strand.points().getLast().y(),"Upward drift lost");
            }
        }
        var state=new AmbientEffectsLayers.State(41);
        Object world=new Object();var layers=List.of(new AmbientEffectsLayers.Layer(AmbientField.Style.LIGHT_STRANDS,1,1));
        state.sample(world,0,false,layers);double time=state.sample(world,1_000_000_000L,false,layers);
        check(time==1,"Clock does not advance");state.reset();
        check(state.sample(new Object(),20_000_000_000L,false,layers)==0,"World/reset retains old lifetime");
        System.out.println("PASS Light Strands: "+checks+" checks; <=72 strands,12 history points,720 quads,864 terrain probes; smooth bounded motion, fade/reset/collision/frustum checks.");
    }
}
