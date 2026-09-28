package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.*;

/** Deterministic checks of the actual production 3D water mesh; no substitute geometry. */
public final class WaterJumpMeshCheck {
    private static int assertions;
    private static int meshes;
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static double radius(WaterJumpMesh.Point p) { return Math.hypot(p.x(), p.z()); }
    private static double distance(WaterJumpMesh.Point a, WaterJumpMesh.Point b) {
        return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
    }
    private static double area(WaterJumpMesh.Point a, WaterJumpMesh.Point b, WaterJumpMesh.Point c) {
        double ux=b.x()-a.x(),uy=b.y()-a.y(),uz=b.z()-a.z();
        double vx=c.x()-a.x(),vy=c.y()-a.y(),vz=c.z()-a.z();
        return .5*Math.sqrt(Math.pow(uy*vz-uz*vy,2)+Math.pow(uz*vx-ux*vz,2)+Math.pow(ux*vy-uy*vx,2));
    }
    private static WaterJumpMesh.Point[] points(WaterJumpMesh.Face f) {
        return new WaterJumpMesh.Point[]{f.a(),f.b(),f.c(),f.d()};
    }
    private static List<WaterJumpMesh.Face> check(double age,double size,double phase,int budget) {
        List<WaterJumpMesh.Face> faces=WaterJumpMesh.build(age,size,phase,budget);meshes++;
        require(faces.size()<=Math.max(0,budget)&&faces.size()<=144,"Wave budget exceeded");
        require(Set.of(0,48,96,144).contains(faces.size()),"Partial ring submitted: "+faces.size());
        double clampedSize=Math.max(.6,Math.min(3,size));
        Map<Integer,Map<Integer,List<WaterJumpMesh.Face>>> rings=new HashMap<>();
        for(WaterJumpMesh.Face face:faces) {
            require(Double.isFinite(face.opacity())&&face.opacity()>0&&face.opacity()<=1,"Invalid opacity");
            require(Float.isFinite(face.uStart())&&Float.isFinite(face.uEnd())&&face.uStart()>=0&&face.uEnd()<=1&&face.uEnd()>face.uStart(),"Invalid UV interval");
            int segments=(int)Math.round(1.0/(face.uEnd()-face.uStart()));
            require(segments==48||segments==24,"Unexpected ring tessellation");
            int segment=Math.round(face.uStart()*segments);
            require(segment>=0&&segment<segments,"Invalid segment index");
            require(Math.abs(face.uStart()-segment/(double)segments)<1e-6&&Math.abs(face.uEnd()-(segment+1.0)/segments)<1e-6,"Discontinuous U mapping");
            rings.computeIfAbsent(segments,key->new HashMap<>()).computeIfAbsent(segment,key->new ArrayList<>()).add(face);
            for(WaterJumpMesh.Point p:points(face)) {
                require(Double.isFinite(p.x())&&Double.isFinite(p.y())&&Double.isFinite(p.z()),"Nonfinite point");
                require(p.y()>=-1e-12&&p.y()<=clampedSize*.255+1e-12,"Low wave vertical bounds exceeded: "+p.y());
                require(radius(p)<=clampedSize*1.35+1e-9,"Radial wave bounds exceeded");
            }
            require(area(face.a(),face.b(),face.c())>1e-14,"Degenerate first triangle");
            require(area(face.a(),face.c(),face.d())>1e-14,"Degenerate second triangle");
        }
        for(var ring:rings.entrySet()) {
            int segments=ring.getKey();Map<Integer,List<WaterJumpMesh.Face>> ordered=ring.getValue();
            require(ordered.size()==segments,"Incomplete closed ring");
            for(int i=0;i<segments;i++) {
                List<WaterJumpMesh.Face> pair=ordered.get(i),next=ordered.get((i+1)%segments);
                require(pair!=null&&next!=null&&pair.size()==2&&next.size()==2,"Expected inner/outer pair per segment");
                require(pair.get(0).c().equals(pair.get(1).c())&&pair.get(0).d().equals(pair.get(1).d()),"Inner/outer sheets split their crest");
                for(int side=0;side<2;side++) {
                    require(pair.get(side).b().equals(next.get(side).a()),"Neighbor base seam at segment "+i);
                    require(pair.get(side).c().equals(next.get(side).d()),"Neighbor crest seam at segment "+i);
                }
            }
            require(ordered.get(0).get(0).uStart()==0f&&ordered.get(segments-1).get(0).uEnd()==1f,"Ring UV wrap endpoints changed");
        }
        return faces;
    }
    private static double height(List<WaterJumpMesh.Face> faces) {
        double value=0;for(var face:faces)for(var p:points(face))value=Math.max(value,p.y());return value;
    }
    private static double outerRadius(List<WaterJumpMesh.Face> faces) {
        double value=0;for(var face:faces){value=Math.max(value,radius(face.a()));value=Math.max(value,radius(face.b()));}return value;
    }
    private static void invalidInputs() {
        for(double age:new double[]{-1,0,1,2,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
            require(WaterJumpMesh.build(age,1.8,.3,144).isEmpty(),"Inactive/invalid age generated a wave");
        for(double size:new double[]{-1,0,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
            require(WaterJumpMesh.build(.4,size,.3,144).isEmpty(),"Invalid size generated a wave");
        for(double phase:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
            require(WaterJumpMesh.build(.4,1.8,phase,144).isEmpty(),"Invalid phase generated a wave");
        for(int budget:new int[]{Integer.MIN_VALUE,-1,0,1,47})
            require(WaterJumpMesh.build(.4,1.8,.3,budget).isEmpty(),"Insufficient budget generated a ring");
    }
    private static void geometry() {
        for(double size:new double[]{.01,.6,1,1.8,3,50})
            for(double phase:new double[]{-3,0,.73,2.4,Math.PI*2,1000})
                for(double age:new double[]{.01,.08,.21,.35,.5,.7,.84,.92,.99})
                    for(int budget:new int[]{0,47,48,95,96,143,144,768}) check(age,size,phase,budget);
        require(WaterJumpMesh.MAX_WAVE_QUADS==144,"Public mesh budget contract changed");
        for(int budget=0;budget<=200;budget++) {
            int expected=budget<48?0:budget<96?48:budget<144?96:144;
            require(check(.4,1.8,.3,budget).size()==expected,"Whole-ring budget priority changed: "+budget);
        }
        require(check(.08,1.8,.3,144).size()==96,"Primary-only early wave missing");
        require(check(.76,1.8,.3,144).size()==48,"Secondary-only late wave missing");
        require(check(.82,1.8,.3,144).isEmpty()&&check(.99,1.8,.3,144).isEmpty(),"Fallen waves persist after their lifetime");
        List<WaterJumpMesh.Face> raised=check(.32,1.8,.3,144);
        require(height(raised)>.20,"Wave is not visibly three-dimensional");
        require(height(raised)>height(check(.025,1.8,.3,144))+.10,"Wave does not rise");
        require(height(raised)>height(check(.65,1.8,.3,144))+.10,"Wave does not fall before expiry");
        double previous=0;
        for(double age:new double[]{.04,.10,.2,.35,.5,.60,.65}) {
            double outer=outerRadius(check(age,1.8,.3,96));
            require(outer>previous,"Primary outer boundary does not expand outward");previous=outer;
        }
        require(check(.4,.01,.3,144).equals(check(.4,.6,.3,144)),"Small size was not clamped consistently");
        require(check(.4,50,.3,144).equals(check(.4,3,.3,144)),"Large size was not clamped consistently");
        try { raised.add(raised.getFirst());throw new AssertionError("Returned mesh is mutable"); }
        catch(UnsupportedOperationException expected) { assertions++; }
    }
    private static void animation() {
        List<WaterJumpMesh.Face> first=check(.4,1.8,.3,144),phase=check(.4,1.8,1.3,144);
        require(!first.equals(phase),"Phase does not change geometry");
        double largest=0;int changed=0;
        List<WaterJumpMesh.Face> next=check(.4+(1.0/60)/1.2,1.8,.3,144);
        require(first.size()==next.size(),"Mid-life fractional frame changed topology");
        for(int i=0;i<first.size();i++) {
            WaterJumpMesh.Point[] a=points(first.get(i)),b=points(next.get(i));
            for(int j=0;j<4;j++){double movement=distance(a[j],b[j]);largest=Math.max(largest,movement);if(movement>1e-7)changed++;}
        }
        require(changed>100,"Fractional frame motion absent");
        require(largest<.35,"Fractional frame geometry jumped: "+largest);
        System.out.printf(Locale.ROOT,"Water motion: %d moving vertices; largest 1/60-second step %.6f blocks%n",changed,largest);
    }
    public static void main(String[] args) {
        invalidInputs();geometry();animation();
        System.out.println("PASS WaterJumpMesh: "+assertions+" assertions over "+meshes+" production meshes; finite low/wide raised/falling surfaces, outward growth, nondegenerate triangles, exact shared seams/U wrap, full-ring budgets and smooth frame motion.");
    }
}
