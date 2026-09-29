package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Nine flat seam paths cover eight compass headings, including small optional backward detours. */
public final class HitGroundPath {
    public static final int BRANCHES = 9, STEPS = 10, MAX_SURFACE_SAMPLES = 384;
    private static final int[] DX={1,0,-1,0}, DZ={0,1,0,-1};
    private static final int[] HX={1,1,0,-1,-1,-1,0,1}, HZ={0,1,1,1,0,-1,-1,-1};
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
        int offset=random.nextInt(8),extra=random.nextInt(8);
        List<Branch> branches=new ArrayList<>(BRANCHES);
        Set<List<Edge>> unique=new HashSet<>();
        Set<Edge> occupied=new HashSet<>();
        for(int branch=0;branch<BRANCHES;branch++) {
            int heading=branch<8?(offset+branch)%8:extra;
            List<Edge> planned=plan(heading,random,occupied,unique);
            unique.add(planned);
            List<Segment> route=new ArrayList<>(STEPS);
            for(Edge edge:planned) {
                // Plan in integer space first. Collision checks only visit the chosen ten edges.
                if(!budget.supported(edge))break;
                int step=route.size();
                route.add(new Segment(new HitEffectsState.Point(originX+edge.x,top+.018,originZ+edge.z),
                    new HitEffectsState.Point(originX+edge.nx,top+.018,originZ+edge.nz),step,step+1));
                occupied.add(edge);
            }
            if(!route.isEmpty())branches.add(new Branch(route));
        }
        return List.copyOf(branches);
    }

    private static List<Edge> plan(int heading,Random random,Set<Edge> occupied,Set<List<Edge>> used) {
        boolean hiccup=random.nextDouble()<.28,crossing=hiccup&&random.nextDouble()<.45;
        List<Edge> best=null;double bestScore=Double.POSITIVE_INFINITY;
        // Three distinct guaranteed routes provide a finite fallback, even for the repeated ninth heading.
        for(int attempt=0;attempt<43;attempt++) {
            List<Edge> candidate=attempt<3?fallback(heading,attempt)
                :crossing&&attempt<7?crossing(heading,attempt-3):wander(heading,random,hiccup);
            if(candidate.isEmpty()||!bends(candidate,heading)||used.contains(candidate))continue;
            double score=attempt<3?.7:0;boolean backwards=false,selfCrossing=false;Set<Long> nodes=new HashSet<>();nodes.add(node(0,0));
            for(int i=0;i<candidate.size();i++) {
                Edge e=candidate.get(i);
                backwards|=(e.nx-e.x)*HX[heading]+(e.nz-e.z)*HZ[heading]<0;
                selfCrossing|=!nodes.add(node(e.nx,e.nz));
                if(i>1&&(occupied.contains(e)||occupied.contains(new Edge(e.nx,e.nz,e.x,e.z))))score+=.6;
            }
            if(hiccup&&!backwards)score+=3;
            if(crossing&&!selfCrossing)score+=8;
            score+=random.nextDouble()*.5;
            if(score<bestScore){best=candidate;bestScore=score;}
        }
        return best==null?List.of():best;
    }

    private static List<Edge> crossing(int heading,int variant) {
        int forward=heading/2,side=(forward+1)%4;
        if((heading&1)==0&&variant%2==1)side=(forward+3)%4;
        if((heading&1)!=0&&variant%2==1){int swap=forward;forward=side;side=swap;}
        int[] directions=(heading&1)==0
            ?new int[]{forward,forward,side,(forward+2)%4,(side+2)%4,(side+2)%4,forward,forward,side,forward}
            :new int[]{forward,side,side,(forward+2)%4,(side+2)%4,forward,forward,side,forward,side};
        // Shift the crossing one block farther out without changing its right-angle pass-through.
        if(variant>=2){System.arraycopy(directions,0,directions,1,STEPS-1);directions[0]=forward;}
        List<Edge> result=new ArrayList<>(STEPS);int x=0,z=0;
        for(int direction:directions){int nx=x+DX[direction],nz=z+DZ[direction];result.add(new Edge(x,z,nx,nz));x=nx;z=nz;}
        return List.copyOf(result);
    }

    private static List<Edge> fallback(int heading,int variant) {
        int forward=heading/2,side=(forward+1)%4;
        if((heading&1)==0&&variant==1)side=(forward+3)%4;
        int[] pattern=(heading&1)==0?switch(variant) {
            case 0->new int[]{0,0,1,0,0,-1,0,1,0,0};
            case 1->new int[]{0,1,1,0,0,-1,0,0,-1,0};
            default->new int[]{0,1,0,0,-1,0,0,1,0,0};
        }:switch(variant) {
            case 0->new int[]{0,0,1,0,1,1,0,1,0,1};
            case 1->new int[]{0,1,1,0,1,0,0,1,1,0};
            default->new int[]{0,0,1,1,1,0,1,0,0,1};
        };
        List<Edge> result=new ArrayList<>(STEPS);int x=0,z=0;
        for(int step=0;step<STEPS;step++) {
            int direction=pattern[step]==0?forward:pattern[step]==1?side:(side+2)%4;
            int nx=x+DX[direction],nz=z+DZ[direction];result.add(new Edge(x,z,nx,nz));x=nx;z=nz;
        }
        return List.copyOf(result);
    }

    private static List<Edge> wander(int heading,Random random,boolean hiccup) {
        List<Edge> result=new ArrayList<>(STEPS);Set<Edge> edges=new HashSet<>();Map<Long,Integer> joints=new HashMap<>();
        int x=0,z=0,backAt=3+random.nextInt(4),backwards=0,continueAcross=-1,previous=-1,run=0;
        for(int step=0;step<STEPS;step++) {
            Edge chosen=null;int chosenDirection=-1;double best=-Double.MAX_VALUE;
            for(int direction=0;direction<4;direction++) {
                int nx=x+DX[direction],nz=z+DZ[direction];
                int forward=DX[direction]*HX[heading]+DZ[direction]*HZ[heading];
                if(continueAcross>=0&&direction!=continueAcross)continue;
                if(direction==previous&&run>=3)continue;
                if(edges.contains(new Edge(x,z,nx,nz))||edges.contains(new Edge(nx,nz,x,z)))continue;
                if(nx*HX[heading]+nz*HZ[heading]<1)continue;
                if(forward<0&&(!hiccup||backwards>=2||step<backAt||step>backAt+1))continue;
                int joint=joints.getOrDefault(node(nx,nz),0);
                // Revisit only a straight interior joint, from its perpendicular axis, with room to exit.
                if(joint!=0&&(step==STEPS-1||joint!=((direction&1)==0?10:5)))continue;
                if(step==STEPS-1&&!insideHeading(nx,nz,heading))continue;
                double score=forward-Math.abs(nx*HZ[heading]-nz*HX[heading])*.20+random.nextDouble()*4.8;
                if(hiccup&&step==backAt&&forward<0)score+=9;
                if(hiccup&&step==backAt-1&&(heading&1)==0&&forward==0)score+=6;
                if(score>best){best=score;chosen=new Edge(x,z,nx,nz);chosenDirection=direction;}
            }
            if(chosen==null)return List.of();
            continueAcross=joints.containsKey(node(chosen.nx,chosen.nz))?chosenDirection:-1;
            joints.merge(node(x,z),1<<chosenDirection,(a,b)->a|b);
            joints.merge(node(chosen.nx,chosen.nz),1<<((chosenDirection+2)%4),(a,b)->a|b);
            if((chosen.nx-x)*HX[heading]+(chosen.nz-z)*HZ[heading]<0)backwards++;
            run=chosenDirection==previous?run+1:1;previous=chosenDirection;
            result.add(chosen);edges.add(chosen);x=chosen.nx;z=chosen.nz;
        }
        return insideHeading(x,z,heading)?List.copyOf(result):List.of();
    }

    private static boolean bends(List<Edge> edges,int heading) {
        int previousX=0,previousZ=0,run=0,longest=0,turns=0,left=0,right=0;
        for(Edge edge:edges) {
            int dx=edge.nx-edge.x,dz=edge.nz-edge.z;
            if(dx==previousX&&dz==previousZ)run++;
            else {if(run>0)turns++;run=1;}
            longest=Math.max(longest,run);previousX=dx;previousZ=dz;
            int side=dx*HZ[heading]-dz*HX[heading];if(side>0)left++;else if(side<0)right++;
        }
        if(longest>3||turns<4)return false;
        // Cardinal routes need several real lateral moves, including a return toward their heading.
        // Diagonal routes mix short staircase runs instead of a perfectly repeating zigzag.
        return (heading&1)==0?left>0&&right>0&&left+right>=3:longest>=2;
    }

    private static boolean insideHeading(int x,int z,int heading) {
        int forward=x*HX[heading]+z*HZ[heading],side=Math.abs(x*HZ[heading]-z*HX[heading]);
        return forward>=((heading&1)==0?4:6)&&side<=forward*.414213562373095;
    }
    private static long node(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}

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
