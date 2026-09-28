package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;

public final class TargetOrbitMeshCheck {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static List<Point> points(Face f) { return List.of(f.a(), f.b(), f.c(), f.d()); }
    public static void main(String[] args) {
        for (double[] bounds : new double[][] {{.6,.6,1.8},{.3,.3,.5},{1.4,.9,2.9}}) {
            double w=bounds[0],d=bounds[1],h=bounds[2];
            for (int frame=0;frame<120;frame++) {
                double phase=frame*Math.PI/30;
                var box=TargetOrbitMesh.box(w,d,h,phase,0xffcc0044,0xffaa3388,0xffffffff,64);
                require(box.size()==10,"Box faces plus four scan bands");
                for (Face face:box) for (Point p:points(face)) {
                    require(Math.abs(p.x())<=w*.5+1e-12 && Math.abs(p.z())<=d*.5+1e-12 && p.y()>=-1e-12 && p.y()<=h+1e-12,"Box never exceeds exact entity bounds");
                    require(Math.abs(Math.abs(p.x())-w*.5)<1e-12 || Math.abs(Math.abs(p.z())-d*.5)<1e-12,"Face stays on exact hitbox plane");
                }
                for(String style:List.of("Crystals","Cubes","Sparks")) {
                    int cost=style.equals("Cubes")?6:8, objects=style.equals("Sparks")?4:3;
                    var mesh=TargetOrbitMesh.build(style,w,d,h,1,phase,0xffcc0044,0xffaa3388,64);
                    require(mesh.size()==cost*objects,"All small objects rendered");
                    for(int object=0;object<objects;object++) {
                        double minX=Double.POSITIVE_INFINITY,maxX=-minX,minY=minX,maxY=-minX,minZ=minX,maxZ=-minX;
                        for(Face f:mesh.subList(object*cost,(object+1)*cost)) for(Point p:points(f)) {
                            require(Double.isFinite(p.x()+p.y()+p.z()),"Finite mesh");
                            minX=Math.min(minX,p.x());maxX=Math.max(maxX,p.x());minY=Math.min(minY,p.y());maxY=Math.max(maxY,p.y());minZ=Math.min(minZ,p.z());maxZ=Math.max(maxZ,p.z());
                        }
                        require(maxX-minX<.31 && maxY-minY<.31 && maxZ-minZ<.31,"Orbit objects remain compact, not full body cages");
                        require(minY>=0 && maxY<=h,"Objects stay beside target body");
                    }
                    for(int budget=0;budget<35;budget++) {
                        int count=TargetOrbitMesh.build(style,w,d,h,1,phase,1,2,budget).size();
                        require(count<=budget && count%cost==0,"Partial budget never cuts an object open");
                    }
                }
            }
        }
        require(TargetOrbitMesh.build("Cubes",Double.NaN,1,1,1,0,1,2,64).isEmpty(),"Invalid dimensions rejected");
        require(TargetOrbitMesh.box(.6,.6,1.8,0,1,2,3,5).isEmpty(),"Insufficient box budget rejected");
        require(!TargetOrbitMesh.build("Crystals",.6,.6,1.8,1,0,1,2,64).equals(TargetOrbitMesh.build("Crystals",.6,.6,1.8,1,.25,1,2,64)),"Orbit advances over time");
        System.out.println("PASS TargetOrbitMesh: "+checks+" checks");
    }
}
