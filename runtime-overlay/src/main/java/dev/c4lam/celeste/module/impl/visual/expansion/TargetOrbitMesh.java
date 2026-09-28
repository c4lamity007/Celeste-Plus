package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Face;
import dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMesh.Point;

/** Small, complete solid objects around the actual target bounds. Pure geometry. */
public final class TargetOrbitMesh {
    public static final int OBJECT_COUNT = 18;
    public static final int MAX_QUADS = OBJECT_COUNT * 8;
    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5));
    private TargetOrbitMesh() { }

    public static List<Face> build(String style, double width, double depth, double height,
                                   double spacing, double phase, int color, int accent, int budget) {
        if (!valid(width, depth, height, spacing, phase) || budget < 6
            || !("Crystals".equals(style) || "Cubes".equals(style) || "Sparks".equals(style))) return List.of();
        boolean cubes = "Cubes".equals(style), sparks = "Sparks".equals(style);
        int cost = cubes ? 6 : 8;
        int count = Math.min(OBJECT_COUNT, budget / cost);
        if (count == 0) return List.of();
        List<Face> result = new ArrayList<>(count * cost);
        double small = Math.min(1, height / .8);
        double size = (sparks ? .029 : cubes ? .045 : .050) * small;
        List<Face> main = cubes ? cube(size, color, accent)
            : SolidVisualMesh.diamond(new Point(0, 0, 0), size, (sparks ? .085 : .115) * small, color, cost);
        List<Face> alternate = cubes ? main
            : SolidVisualMesh.diamond(new Point(0, 0, 0), size, (sparks ? .085 : .115) * small, accent, cost);
        for (int i = 0; i < count; i++) {
            // Six objects per tilted band, staggered around the whole target.
            // Smaller budgets still distribute complete objects around a full turn.
            int band = i % 3;
            double angle = phase + TAU * i / count;
            double x = Math.cos(angle) * (width * .5 + .20 * spacing + .035);
            double z = Math.sin(angle) * (depth * .5 + .20 * spacing + .035);
            double y = height * (.26 + .24 * band + .055 * Math.sin(angle + band * TAU / 3));
            Frame frame = inwardFrame(x, y, z, height * .5, phase * .8 + i * GOLDEN_ANGLE);
            List<Face> local = i % 2 == 0 ? main : alternate;
            for (Face face : local) result.add(new Face(
                place(face.a(), frame, cubes), place(face.b(), frame, cubes),
                place(face.c(), frame, cubes), place(face.d(), frame, cubes),
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
    private record Frame(Point center, Point side, Point inward, Point across) { }
    private static Frame inwardFrame(double x, double y, double z, double targetY, double roll) {
        double horizontal = Math.hypot(x, z);
        double length = Math.hypot(horizontal, targetY - y);
        Point inward = new Point(-x / length, (targetY - y) / length, -z / length);
        Point side = new Point(z / horizontal, 0, -x / horizontal);
        Point across = new Point(-side.z() * inward.y(), side.z() * inward.x() - side.x() * inward.z(),
            side.x() * inward.y());
        double c = Math.cos(roll), s = Math.sin(roll);
        return new Frame(new Point(x, y, z), mix(side, across, c, s), inward, mix(side, across, -s, c));
    }
    private static Point mix(Point a, Point b, double scaleA, double scaleB) {
        return new Point(a.x() * scaleA + b.x() * scaleB, a.y() * scaleA + b.y() * scaleB,
            a.z() * scaleA + b.z() * scaleB);
    }
    private static Point place(Point p, Frame frame, boolean cube) {
        double x = p.x(), y = p.y(), z = p.z();
        if (cube) {
            // Aim a cube corner along the same inward axis as the crystal tips.
            x = (p.x() - p.z()) / Math.sqrt(2);
            y = (p.x() + p.y() + p.z()) / Math.sqrt(3);
            z = (p.x() - 2 * p.y() + p.z()) / Math.sqrt(6);
        }
        return new Point(frame.center().x() + frame.side().x() * x + frame.inward().x() * y + frame.across().x() * z,
            frame.center().y() + frame.side().y() * x + frame.inward().y() * y + frame.across().y() * z,
            frame.center().z() + frame.side().z() * x + frame.inward().z() * y + frame.across().z() * z);
    }
    private static boolean valid(double w,double d,double h,double s,double t) {
        return w>0 && d>0 && h>0 && s>0 && w<64 && d<64 && h<64 && s<=4
            && Double.isFinite(w+d+h+s+t);
    }
}
