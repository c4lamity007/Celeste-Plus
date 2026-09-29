package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** One-block cardinal decisions on block seams, with strictly increasing distance from the hit. */
public final class HitGroundPath {
    public static final int BRANCHES = 4, STEPS = 7, MAX_SURFACE_SAMPLES = 256;
    private static final int[] DX={1,0,-1,0}, DZ={0,1,0,-1};
    public record Segment(HitEffectsState.Point from, HitEffectsState.Point to, double start, double end) { }
    public record Branch(List<Segment> segments) { public Branch { segments=List.copyOf(segments); } }
    public interface Surface {
        /** Return a collision top near expectedY, or NaN. No game state may be changed. */
        double height(double x,double z,double expectedY,double rise,double drop);
    }
    private HitGroundPath() { }

    public static List<Branch> build(double x,double y,double z,long seed,Surface surface) {
        if (surface == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return List.of();
        double originX=Math.rint(x),originZ=Math.rint(z);
        Budget budget=new Budget(surface);
        double originY=budget.height(originX,originZ,y,.16,2.0);
        if (!Double.isFinite(originY)) return List.of();
        Random random=new Random(seed);
        List<Branch> result=new ArrayList<>(BRANCHES);
        int offset=random.nextInt(4);
        for(int branch=0;branch<BRANCHES;branch++) {
            List<Segment> segments=new ArrayList<>();
            double px=originX,pz=originZ,py=originY;
            int previous=(branch+offset)%4;
            for(int step=0;step<STEPS;step++) {
                int start=step==0 ? previous : random.nextInt(4);
                boolean added=false;
                for(int attempt=0;attempt<(step==0?1:4);attempt++) {
                    int direction=(start+attempt)%4;
                    if(step>0 && direction==(previous+2)%4) continue;
                    double nx=px+DX[direction],nz=pz+DZ[direction];
                    if(distance(nx,nz,originX,originZ)<=distance(px,pz,originX,originZ)) continue;
                    List<Segment> edge=edge(px,py,pz,nx,nz,step,budget);
                    if(edge.isEmpty()) continue;
                    segments.addAll(edge);
                    px=nx; pz=nz; py=edge.getLast().to().y()-.018; previous=direction;
                    added=true; break;
                }
                if(!added) break;
            }
            if(!segments.isEmpty()) result.add(new Branch(segments));
        }
        return List.copyOf(result);
    }

    private static List<Segment> edge(double x,double y,double z,double nx,double nz,int step,Budget surface) {
        // Quarter-block samples resolve slab and stair halves. Nothing is queried while drawing.
        List<Segment> result=new ArrayList<>(8);
        HitEffectsState.Point previous=new HitEffectsState.Point(x,y+.018,z);
        double height=y;
        for(int sample=1;sample<=4;sample++) {
            double t=sample*.25,sx=x+(nx-x)*t,sz=z+(nz-z)*t;
            double top=surface.height(sx,sz,height,.51,.51);
            if(!Double.isFinite(top)) return List.of();
            HitEffectsState.Point next=new HitEffectsState.Point(sx,top+.018,sz);
            double begin=step+(sample-1)*.25,end=step+t;
            if(Math.abs(top-height)>.025) {
                // A short vertical riser keeps the light attached to a real stair rather than sloping through it.
                HitEffectsState.Point corner=new HitEffectsState.Point(sx,previous.y(),sz);
                result.add(new Segment(previous,corner,begin,begin+.23));
                result.add(new Segment(corner,next,begin+.23,end));
            } else result.add(new Segment(previous,next,begin,end));
            previous=next; height=top;
        }
        return result;
    }
    private static double distance(double x,double z,double ox,double oz) { return (x-ox)*(x-ox)+(z-oz)*(z-oz); }
    private static final class Budget implements Surface {
        private final Surface delegate;
        private int remaining=MAX_SURFACE_SAMPLES;
        private Budget(Surface surface) { delegate=surface; }
        @Override public double height(double x,double z,double y,double rise,double drop) {
            return remaining-->0 ? delegate.height(x,z,y,rise,drop) : Double.NaN;
        }
    }
}
