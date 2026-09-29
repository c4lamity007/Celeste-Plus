package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Flat one-block seam paths. Four shared exits fan into nine distinct outward routes. */
public final class HitGroundPath {
    public static final int BRANCHES = 9, STEPS = 7, MAX_SURFACE_SAMPLES = 256;
    private static final int[] DX={1,0,-1,0}, DZ={0,1,0,-1};
    public record Segment(HitEffectsState.Point from, HitEffectsState.Point to, double start, double end) { }
    public record Branch(List<Segment> segments) { public Branch { segments=List.copyOf(segments); } }
    public interface Surface {
        /** Return a collision top near expectedY, or NaN. No game state may be changed. */
        double height(double x,double z,double expectedY,double rise,double drop);
    }
    private record Edge(int x,int z,int nx,int nz) { }
    private HitGroundPath() { }

    public static List<Branch> build(double x,double y,double z,long seed,Surface surface) {
        if(surface==null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return List.of();
        double originX=Math.rint(x),originZ=Math.rint(z);
        Budget budget=new Budget(surface,originX,originZ);
        double top=budget.initial(y);
        if(!Double.isFinite(top)) return List.of();
        budget.top=top;
        Random random=new Random(seed);
        int offset=random.nextInt(4);
        int[][] second=new int[4][3];
        for(int direction=0;direction<4;direction++) {
            second[direction]=new int[]{direction,(direction+1)%4,(direction+3)%4};
            for(int i=2;i>0;i--){int j=random.nextInt(i+1),swap=second[direction][i];second[direction][i]=second[direction][j];second[direction][j]=swap;}
        }
        List<Branch> branches=new ArrayList<>(BRANCHES);
        Set<List<Segment>> unique=new HashSet<>();
        Set<Edge> occupied=new HashSet<>();
        for(int branch=0;branch<BRANCHES;branch++) {
            List<Segment> route=new ArrayList<>(STEPS);
            int px=0,pz=0,exit=(offset+branch%4)%4;
            for(int step=0;step<STEPS;step++) {
                int preferred=step==0?exit:step==1?second[exit][branch/4]:random.nextInt(4);
                Edge chosen=null;
                // Prefer a new edge after the deliberately shared initial prefix.
                for(int pass=0;pass<2 && chosen==null;pass++) for(int attempt=0;attempt<4;attempt++) {
                    int direction=(preferred+attempt)%4;
                    if(step==0 && direction!=exit) continue;
                    int nx=px+DX[direction],nz=pz+DZ[direction];
                    if(nx*nx+nz*nz<=px*px+pz*pz) continue;
                    Edge edge=new Edge(px,pz,nx,nz);
                    if(pass==0 && step>1 && occupied.contains(edge)) continue;
                    if(!budget.supported(edge)) continue;
                    chosen=edge;break;
                }
                if(chosen==null) break;
                route.add(new Segment(new HitEffectsState.Point(originX+px,top+.018,originZ+pz),
                    new HitEffectsState.Point(originX+chosen.nx,top+.018,originZ+chosen.nz),step,step+1));
                occupied.add(chosen);px=chosen.nx;pz=chosen.nz;
            }
            if(!route.isEmpty() && unique.add(List.copyOf(route)))branches.add(new Branch(route));
        }
        return List.copyOf(branches);
    }

    private static final class Budget {
        private final Surface surface;
        private final double x,z;
        private final Map<Edge,Boolean> edges=new HashMap<>();
        private int remaining=MAX_SURFACE_SAMPLES;
        private double top;
        Budget(Surface surface,double x,double z){this.surface=surface;this.x=x;this.z=z;}
        double initial(double y){remaining--;return surface.height(x,z,y,.16,2);}
        boolean supported(Edge edge) {
            Boolean cached=edges.get(edge);if(cached!=null)return cached;
            boolean supported=true;
            // Quarter-block collision checks locate gaps/slab edges; rendered geometry stays one full block.
            for(int i=1;i<=4;i++) {
                if(remaining--<=0){supported=false;break;}
                double t=i*.25,atX=x+edge.x+(edge.nx-edge.x)*t,atZ=z+edge.z+(edge.nz-edge.z)*t;
                double sample=surface.height(atX,atZ,top,.02,.02);
                if(!Double.isFinite(sample) || Math.abs(sample-top)>.0001){supported=false;break;}
            }
            edges.put(edge,supported);return supported;
        }
    }
}
