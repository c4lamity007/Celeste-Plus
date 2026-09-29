package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;

/** Bounded flat light trails on integer XY, XZ or YZ grid planes. */
public final class AmbientTronField {
    public static final int MAX_TRAILS = 24 * AmbientField.DENSITY_COPIES;
    public static final int MAX_SEGMENTS_PER_TRAIL = 8;
    public static final int MAX_AIR_QUERIES = MAX_TRAILS * MAX_SEGMENTS_PER_TRAIL * 4;
    private static final int STEPS = 36;
    @FunctionalInterface public interface AirCell { boolean isAir(int x, int y, int z); }
    public record Segment(long trail, double ax, double ay, double az, double bx, double by, double bz,
                          double width, float startOpacity, float endOpacity, double palette,
                          int normalAxis, int previousDirection, int nextDirection) {
        public Segment(long trail, double ax, double ay, double az, double bx, double by, double bz,
                       double width, float startOpacity, float endOpacity, double palette) {
            this(trail,ax,ay,az,bx,by,bz,width,startOpacity,endOpacity,palette,1,-1,-1);
        }
    }

    private static final class Trail {
        final double[] x = new double[STEPS + 1], y = new double[STEPS + 1], z = new double[STEPS + 1];
        final int[] direction = new int[STEPS];
        final boolean[] clear = new boolean[STEPS];
        final long[] checked = new long[STEPS];
        long identity;
        int normalAxis;
        double birth, life, speed, tail, palette;
        Trail() { java.util.Arrays.fill(checked, Long.MIN_VALUE); }
    }
    private final long seed;
    private final Trail[] slots = new Trail[MAX_TRAILS];
    private long serial;
    private double lastSeconds = Double.NaN, lastX, lastY, lastZ;

    public AmbientTronField(long seed) { this.seed = seed; }
    public void reset() { java.util.Arrays.fill(slots, null); lastSeconds = Double.NaN; serial = 0; }

    /** Fixed cell probes only, once per time slice; this never searches or loads terrain. */
    public List<Segment> sample(double eyeX, double eyeY, double eyeZ, double seconds,
                                double density, double size, AirCell air) {
        if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
            || !Double.isFinite(seconds) || seconds < 0 || Math.abs(eyeX) > 3.1E7
            || Math.abs(eyeZ) > 3.1E7 || Math.abs(eyeY) > 1.0E6 || air == null) return List.of();
        if (Double.isFinite(lastSeconds) && (seconds < lastSeconds
            || squared(eyeX - lastX, eyeY - lastY, eyeZ - lastZ) > 48 * 48)) reset();
        lastSeconds = seconds; lastX = eyeX; lastY = eyeY; lastZ = eyeZ;
        density = finiteRange(density, 0, 1, .6); size = finiteRange(size, .5, 2, 1);
        int count = trailCount(density);
        if (count == 0) return List.of();
        long slice = (long)Math.floor(seconds * 20);
        List<Segment> out = new ArrayList<>(count * MAX_SEGMENTS_PER_TRAIL);
        for (int slot = 0; slot < count; slot++) {
            Trail trail = slots[slot];
            if (trail == null || seconds >= trail.birth + trail.life) {
                trail = spawn(slot, eyeX, eyeY, eyeZ, seconds, trail == null);
                slots[slot] = trail;
            }
            double age = seconds - trail.birth;
            double life = smooth(age / .65) * smooth((trail.life - age) / 1.1);
            double weight = smooth(Math.min(1, density * MAX_TRAILS - slot));
            double head = Math.min(STEPS, Math.max(0, age * trail.speed));
            double tail = Math.max(0, head - trail.tail);
            int from = (int)Math.floor(tail), to = Math.min(STEPS, (int)Math.ceil(head));
            for (int step = from; step < to; step++) {
                double begin = Math.max(tail, step), end = Math.min(head, step + 1);
                if (end - begin < 1.0E-7) continue;
                double ax = lerp(trail.x[step], trail.x[step + 1], begin - step);
                double ay = lerp(trail.y[step], trail.y[step + 1], begin - step);
                double az = lerp(trail.z[step], trail.z[step + 1], begin - step);
                double bx = lerp(trail.x[step], trail.x[step + 1], end - step);
                double by = lerp(trail.y[step], trail.y[step + 1], end - step);
                double bz = lerp(trail.z[step], trail.z[step + 1], end - step);
                double visibility = visibility((ax + bx) * .5 - eyeX, (ay + by) * .5 - eyeY, (az + bz) * .5 - eyeZ);
                if (visibility * life * weight < .006) continue;
                if (trail.checked[step] != slice) {
                    trail.checked[step] = slice;
                    trail.clear[step] = edgeVisible(trail.x[step],trail.y[step],trail.z[step],
                        trail.x[step+1],trail.y[step+1],trail.z[step+1],trail.direction[step],air);
                }
                if (!trail.clear[step]) continue;
                double alpha = life * weight * visibility * .62;
                out.add(new Segment(trail.identity, ax, ay, az, bx, by, bz, .11 * size,
                    (float)(alpha * trailFade(begin, head, trail.tail)),
                    (float)(alpha * trailFade(end, head, trail.tail)), trail.palette,trail.normalAxis,
                    begin==step && step>0 ? trail.direction[step-1] : -1,
                    end==step+1 && step+1<STEPS ? trail.direction[step+1] : -1));
            }
        }
        return List.copyOf(out);
    }

    private Trail spawn(int slot, double eyeX, double eyeY, double eyeZ, double seconds, boolean initial) {
        long key = mix(seed ^ (++serial * 0x632BE59BD9B4E019L) ^ slot);
        Trail t = new Trail(); t.identity = key;
        t.life = 7.0 + unit(key + 1) * 3.0; t.speed = 1.5 + unit(key + 2) * 1.6;
        t.tail = 4 + unit(key + 3) * 2.7; t.palette = unit(key + 4);
        t.birth = seconds - (initial ? unit(key + 5) * (t.life - 1.2) : 0);
        t.x[0] = Math.floor(eyeX + (unit(key + 6) * 2 - 1) * 13);
        t.z[0] = Math.floor(eyeZ + (unit(key + 7) * 2 - 1) * 13);
        t.y[0] = Math.floor(eyeY + (unit(key + 8) * 2 - 1) * 8);
        t.normalAxis = (int)(unit(key+9)*3);
        int direction = (int)(unit(key + 10) * 4);
        for (int step = 0; step < STEPS; step++) {
            int selected = direction;
            double best = -Double.MAX_VALUE;
            for (int turn = -1; turn <= 1; turn++) {
                int candidate = (direction + turn + 4) & 3;
                int worldDirection = planeDirection(t.normalAxis,candidate);
                int dx=component(worldDirection,0),dy=component(worldDirection,1),dz=component(worldDirection,2);
                double nx=t.x[step]+dx,ny=t.y[step]+dy,nz=t.z[step]+dz;
                boolean visited = false;
                for (int prior=0;prior<=step;prior++) if(t.x[prior]==nx && t.y[prior]==ny && t.z[prior]==nz) { visited=true;break; }
                double away=(nx-t.x[0])*dx+(ny-t.y[0])*dy+(nz-t.z[0])*dz;
                double score = unit(key + 32 + step * 3L + turn) + Math.min(4, away) * .065 - (visited ? 10 : 0);
                if (score > best) { best = score; selected = candidate; }
            }
            direction = selected;
            int worldDirection=planeDirection(t.normalAxis,direction);t.direction[step]=worldDirection;
            t.x[step+1]=t.x[step]+component(worldDirection,0);
            t.y[step+1]=t.y[step]+component(worldDirection,1);
            t.z[step+1]=t.z[step]+component(worldDirection,2);
        }
        return t;
    }

    public static int trailCount(double density) { return (int)Math.ceil(finiteRange(density,0,1,.6)*MAX_TRAILS); }

    private static int planeDirection(int normalAxis,int direction) {
        int axis=(normalAxis+1+(direction&1))%3;
        return axis*2+(direction<2?0:1);
    }
    static int component(int direction,int axis) {
        return direction>=0 && direction/2==axis ? ((direction&1)==0?1:-1) : 0;
    }
    /** A grid edge borders four cells; any air-adjacent side may show its depth-tested ribbon. */
    static boolean edgeVisible(double ax,double ay,double az,double bx,double by,double bz,int direction,AirCell air) {
        int axis=direction/2,first=(axis+1)%3,second=(axis+2)%3;
        int[] cell={(int)Math.floor((ax+bx)*.5),(int)Math.floor((ay+by)*.5),(int)Math.floor((az+bz)*.5)};
        int a=cell[first],b=cell[second];boolean visible=false;
        for(int i=-1;i<=0;i++)for(int j=-1;j<=0;j++) {
            cell[first]=a+i;cell[second]=b+j;
            visible |= air.isAir(cell[0],cell[1],cell[2]);
        }
        return visible;
    }

    private static double trailFade(double at, double head, double length) { return smooth(1 - (head - at) / length); }
    private static double visibility(double x, double y, double z) {
        double radius = Math.sqrt((x*x + z*z) / (25*25) + y*y / (13*13));
        return smooth((1 - radius) / .3) * smooth((Math.sqrt(x*x + y*y + z*z) - 1.2) / 1.5);
    }
    private static double finiteRange(double v, double min, double max, double fallback) { return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback; }
    private static double smooth(double v) { v = Math.max(0, Math.min(1, v)); return v*v*(3 - 2*v); }
    private static double squared(double x, double y, double z) { return x*x + y*y + z*z; }
    private static double lerp(double a, double b, double t) { return a + (b-a)*t; }
    private static long mix(long x) { x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L; x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL; return x ^ (x >>> 31); }
    private static double unit(long x) { return (mix(x) >>> 11) * 0x1.0p-53; }
}
