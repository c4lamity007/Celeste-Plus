package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;

/** Small, complete solid objects around the actual target bounds. Pure geometry. */
public final class TargetOrbitMesh {
    private static final double TAU = Math.PI * 2;
    private TargetOrbitMesh() { }

    public static List<Face> build(String style, double width, double depth, double height,
                                   double spacing, double phase, int color, int accent, int budget) {
        if (!valid(width, depth, height, spacing, phase) || budget < 4) return List.of();
        boolean cubes = "Cubes".equals(style), sparks = "Sparks".equals(style);
        int count = sparks ? 4 : 3, cost = cubes ? 6 : 8;
        List<Face> result = new ArrayList<>();
        double small = Math.min(1, height / .8);
        for (int i = 0; i < count && result.size() + cost <= Math.min(64, budget); i++) {
            double angle = phase + TAU * i / count;
            double x = Math.cos(angle) * (width * .5 + .18 * spacing);
            double z = Math.sin(angle) * (depth * .5 + .18 * spacing);
            double y = height * (.50 + .18 * Math.sin(angle * (sparks ? 2 : 1)));
            double size = (sparks ? .038 : cubes ? .065 : .070) * small;
            List<Face> local = cubes ? cube(size, color, accent)
                : SolidVisualMesh.diamond(new Point(0, 0, 0), size, (sparks ? .12 : .145) * small,
                    i % 2 == 0 ? color : accent, cost);
            for (Face face : local) result.add(new Face(
                rotate(face.a(), x, y, z, angle * 1.3, sparks ? Math.PI * .5 : .22),
                rotate(face.b(), x, y, z, angle * 1.3, sparks ? Math.PI * .5 : .22),
                rotate(face.c(), x, y, z, angle * 1.3, sparks ? Math.PI * .5 : .22),
                rotate(face.d(), x, y, z, angle * 1.3, sparks ? Math.PI * .5 : .22),
                face.color(), face.endColor(), face.uStart(), face.uEnd()));
        }
        return List.copyOf(result);
    }

    /** Six exact hitbox faces plus four inset-height scan bands; no inflated corners. */
    public static List<Face> box(double width, double depth, double height, double phase,
                                int color, int accent, int band, int budget) {
        if (!valid(width, depth, height, 1, phase) || budget < 6) return List.of();
        double x = width * .5, z = depth * .5;
        List<Face> result = new ArrayList<>(10);
        Point a = new Point(-x, 0, -z), b = new Point(x, 0, -z), c = new Point(x, height, -z), d = new Point(-x, height, -z);
        Point e = new Point(-x, 0, z), f = new Point(x, 0, z), g = new Point(x, height, z), h = new Point(-x, height, z);
        result.add(face(a,b,c,d,color,accent)); result.add(face(f,e,h,g,color,accent));
        result.add(face(e,a,d,h,color,accent)); result.add(face(b,f,g,c,color,accent));
        result.add(face(d,c,g,h,accent,color)); result.add(face(e,f,b,a,accent,color));
        if (budget >= 10) {
            double half = Math.min(.012, height * .1);
            double y = half + (height - half * 2) * (.5 + .5 * Math.sin(phase));
            Point[] low = {new Point(-x,y-half,-z), new Point(x,y-half,-z), new Point(x,y-half,z), new Point(-x,y-half,z)};
            Point[] high = {new Point(-x,y+half,-z), new Point(x,y+half,-z), new Point(x,y+half,z), new Point(-x,y+half,z)};
            for (int i=0;i<4;i++) { int next=(i+1)%4; result.add(face(low[i],low[next],high[next],high[i],band,band)); }
        }
        return List.copyOf(result);
    }

    private static List<Face> cube(double s, int color, int accent) {
        Point a=new Point(-s,-s,-s), b=new Point(s,-s,-s), c=new Point(s,s,-s), d=new Point(-s,s,-s);
        Point e=new Point(-s,-s,s), f=new Point(s,-s,s), g=new Point(s,s,s), h=new Point(-s,s,s);
        return List.of(face(a,b,c,d,color,accent),face(f,e,h,g,color,accent),face(e,a,d,h,accent,color),
            face(b,f,g,c,accent,color),face(d,c,g,h,color,accent),face(e,f,b,a,color,accent));
    }
    private static Face face(Point a,Point b,Point c,Point d,int color,int accent) { return new Face(a,b,c,d,color,accent,0,1); }
    private static Point rotate(Point p,double x,double y,double z,double yaw,double tilt) {
        double py=p.y()*Math.cos(tilt)-p.z()*Math.sin(tilt), pz=p.y()*Math.sin(tilt)+p.z()*Math.cos(tilt);
        return new Point(x+p.x()*Math.cos(yaw)+pz*Math.sin(yaw), y+py, z-p.x()*Math.sin(yaw)+pz*Math.cos(yaw));
    }
    private static boolean valid(double w,double d,double h,double s,double t) {
        return w>0 && d>0 && h>0 && s>0 && w<64 && d<64 && h<64 && s<=4
            && Double.isFinite(w+d+h+s+t);
    }
}
