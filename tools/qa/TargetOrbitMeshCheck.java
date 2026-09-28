package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;

public final class TargetOrbitMeshCheck {
    private static final List<String> STYLES = List.of("Crystals", "Cubes", "Sparks");
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static List<Point> points(Face f) { return List.of(f.a(), f.b(), f.c(), f.d()); }
    private static Point subtract(Point a, Point b) { return new Point(a.x()-b.x(), a.y()-b.y(), a.z()-b.z()); }
    private static double dot(Point a, Point b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    private static double distance(Point a, Point b) { Point d=subtract(a,b); return Math.sqrt(dot(d,d)); }
    private static double alignment(Point a, Point b) { return dot(a,b)/Math.sqrt(dot(a,a)*dot(b,b)); }
    private static Point center(List<Face> faces) {
        double minX=Double.POSITIVE_INFINITY, maxX=-minX, minY=minX, maxY=-minX, minZ=minX, maxZ=-minX;
        for(Face f:faces) for(Point p:points(f)) {
            minX=Math.min(minX,p.x()); maxX=Math.max(maxX,p.x());
            minY=Math.min(minY,p.y()); maxY=Math.max(maxY,p.y());
            minZ=Math.min(minZ,p.z()); maxZ=Math.max(maxZ,p.z());
        }
        return new Point((minX+maxX)*.5,(minY+maxY)*.5,(minZ+maxZ)*.5);
    }
    private static int cost(String style) { return style.equals("Cubes") ? 6 : 8; }
    private static List<Face> build(String style, double w, double d, double h, double spacing, double phase, int budget) {
        return TargetOrbitMesh.build(style,w,d,h,spacing,phase,0xffcc0044,0xffaa3388,budget);
    }
    private static void checkOrbit(String style, double w, double d, double h, double spacing, double phase) {
        int cost=cost(style);
        var mesh=build(style,w,d,h,spacing,phase,144);
        require(mesh.size()==cost*18,"Every orbit style draws 18 complete objects");
        double[] heights = new double[18];
        int aboveHead = 0, besideBody = 0, headTransition = 0;
        for(int object=0;object<18;object++) {
            var faces=mesh.subList(object*cost,(object+1)*cost);
            Point center=center(faces);
            Point tip=style.equals("Cubes") ? faces.get(1).d() : faces.getFirst().a();
            Point inward=subtract(new Point(0,h*.5,0),center);
            require(alignment(subtract(tip,center),inward)>1-1e-10,"Every tip/cube corner points at target center");
            heights[object] = center.y() / h;
            require(heights[object]>=.20 && heights[object]<=1.25,"Centers span body through above head");
            if(center.y()>h) {
                aboveHead++;
                require(tip.y()<center.y(),"Above-head tip/cube corner tilts downward toward target");
            }
            if(center.y()<h*.9) besideBody++;
            if(center.y()>=h*.78 && center.y()<=h*1.05) headTransition++;
            for(Face f:faces) for(Point p:points(f)) {
                require(Double.isFinite(p.x()+p.y()+p.z()),"Finite mesh");
                require(distance(p,center)<.12,"Individual objects remain compact");
                require(p.y()>=-1e-12 && p.y()<=h*1.4+1e-12,"Objects stay in bounded body/head orbit volume");
            }
            if(object>0) {
                Point previous=center(mesh.subList((object-1)*cost,object*cost));
                require(distance(center,previous)>.02,"Objects have distinct positions");
            }
        }
        require(aboveHead>=3,"Several objects remain above the head at every phase");
        require(besideBody>=9,"At least half of the objects remain beside the body");
        require(headTransition>=3,"Objects bridge the previous upper edge and head");
        java.util.Arrays.sort(heights);
        require(heights[0]<=.30 && heights[17]>=1.16,"Full vertical span stays populated");
        for(int i=1;i<heights.length;i++)
            require(heights[i]-heights[i-1]<=.13,"No empty height band separates body and overhead objects");
        var advanced=build(style,w,d,h,spacing,phase+.10,144);
        for(int object=0;object<18;object++) {
            Point before=center(mesh.subList(object*cost,(object+1)*cost));
            Point after=center(advanced.subList(object*cost,(object+1)*cost));
            require(distance(before,after)>.005,"Every object actually travels around the target");
            require(before.x()*after.z()-before.z()*after.x()>0,"All objects orbit in a consistent direction");
        }
    }
    private static void checkBox(double w, double d, double h, double phase) {
        var box=TargetOrbitMesh.box(w,d,h,phase,0xffcc0044,0xffaa3388,0xffffffff,144);
        require(box.size()==10,"Box faces plus four scan bands");
        for(Face face:box) for(Point p:points(face)) {
            require(Math.abs(p.x())<=w*.5+1e-12 && Math.abs(p.z())<=d*.5+1e-12 && p.y()>=-1e-12 && p.y()<=h+1e-12,
                "Box never exceeds exact entity bounds");
            require(Math.abs(Math.abs(p.x())-w*.5)<1e-12 || Math.abs(Math.abs(p.z())-d*.5)<1e-12,
                "Face stays on exact hitbox plane");
        }
        require(box.equals(TargetOrbitMesh.box(w,d,h,phase,0xffcc0044,0xffaa3388,0xffffffff,64)),
            "Raised orbit budget leaves Box geometry unchanged");
    }
    public static void main(String[] args) {
        for(double[] bounds:new double[][] {{.6,.6,1.8},{.3,.3,.5},{1.4,.9,2.9},{.1,.1,.08}}) {
            double w=bounds[0],d=bounds[1],h=bounds[2];
            for(int frame=0;frame<120;frame++) {
                double phase=frame*Math.PI/30;
                checkBox(w,d,h,phase);
                for(String style:STYLES) checkOrbit(style,w,d,h,frame%2==0?.7:1.6,phase);
            }
        }
        for(String style:STYLES) {
            int cost=cost(style);
            for(int budget=-2;budget<=200;budget++) {
                var mesh=build(style,.6,.6,1.8,1,.4,budget);
                require(mesh.size()==Math.min(18,Math.max(0,budget)/cost)*cost,"Budget emits only complete objects");
                int count=mesh.size()/cost;
                if(count>2) {
                    double sumX=0,sumZ=0;
                    for(int object=0;object<count;object++) {
                        Point at=center(mesh.subList(object*cost,(object+1)*cost));
                        sumX+=at.x();sumZ+=at.z();
                    }
                    require(Math.abs(sumX)<1e-10 && Math.abs(sumZ)<1e-10,"Small budgets still cover a full orbit");
                }
            }
            var start=build(style,.6,.6,1.8,1,0,144);
            var fullTurn=build(style,.6,.6,1.8,1,Math.PI*2,144);
            for(int object=0;object<18;object++) {
                var a=start.subList(object*cost,(object+1)*cost);
                var b=fullTurn.subList(object*cost,(object+1)*cost);
                require(distance(center(a),center(b))<1e-10,"Orbit loops smoothly back to its center");
                require(distance(a.getFirst().b(),b.getFirst().b())>.01,"Objects roll independently while keeping target aim");
            }
            require(build(style,.6,.6,1.8,1,0,0).isEmpty(),"No render allocation emits nothing");
            require(build(style,.6,.6,1.8,1,0,Integer.MAX_VALUE).size()==18*cost,"Unbounded input budget stays capped");
            require(build(style,.6,.6,1.8,1,Double.POSITIVE_INFINITY,144).isEmpty(),"Infinite phase rejected");
            require(build(style,Double.NaN,1,1,1,0,144).isEmpty(),"Invalid dimensions rejected");
            require(build(style,1,1,-1,1,0,144).isEmpty(),"Negative height rejected");
        }
        require(build("Box",.6,.6,1.8,1,0,144).isEmpty(),"Box never accidentally emits orbit objects");
        require(build("unknown",.6,.6,1.8,1,0,144).isEmpty(),"Unknown style emits nothing");
        require(TargetOrbitMesh.box(.6,.6,1.8,0,1,2,3,5).isEmpty(),"Insufficient box budget rejected");
        System.out.println("PASS TargetOrbitMesh: "+checks+" checks");
    }
}
