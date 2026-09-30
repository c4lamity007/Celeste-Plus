package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** World-anchored firefly drift with a short analytic tail; no retained history or game particles. */
public final class AmbientLightStrands {
    public static final int MAX_STRANDS = 24 * AmbientField.DENSITY_COPIES;
    public static final int POINTS = 12;
    public static final int SPRITES_PER_STRAND = 10;
    public static final int MAX_QUADS = MAX_STRANDS * SPRITES_PER_STRAND;
    private static final double TAU = Math.PI * 2;
    public record Point(double x, double y, double z) { }
    public record Strand(List<Point> points, double opacity, double palette) { }
    private record Candidate(int x,int y,int z,int lane,Point head,double age,double time,
                             double opacity,double distance,double palette) { }
    @FunctionalInterface public interface Air { boolean clear(int x,int y,int z); }
    @FunctionalInterface public interface Visible { boolean visible(double x,double y,double z,double radius); }
    private AmbientLightStrands() { }

    public static int strandCount(double density) {
        return Double.isFinite(density) ? (int)Math.round(MAX_STRANDS * Math.clamp(density,0,1)) : 0;
    }

    public static List<Strand> sample(double eyeX,double eyeY,double eyeZ,double seconds,double density,
                                      Air air,Visible visible) {
        int cap = strandCount(density);
        if (cap == 0 || air == null || visible == null || !Double.isFinite(eyeX + eyeY + eyeZ + seconds)) return List.of();
        int cx=(int)Math.floor(eyeX/AmbientField.CELL), cy=(int)Math.floor(eyeY/AmbientField.CELL), cz=(int)Math.floor(eyeZ/AmbientField.CELL);
        List<Candidate> candidates = new ArrayList<>(384);
        for(int x=cx-5;x<=cx+5;x++) for(int z=cz-5;z<=cz+5;z++) for(int y=cy-3;y<=cy+3;y++) {
            if (!AmbientField.mayBeVisible(x,y,z,eyeX,eyeY,eyeZ)) continue;
            for(int lane=0;lane<AmbientField.DENSITY_COPIES;lane++) {
                long seed=seed(x,y,z,lane);
                double life=lifetime(seed), offset=unit(mix(seed+4))*life;
                double cycle=(seconds+offset)/life, age=(cycle-Math.floor(cycle))*life;
                double local=seconds-age; // Birth identity is stable between frames and camera moves.
                Point head=point(x,y,z,seed,age,local);
                double alpha=AmbientField.smooth(age/.9)*AmbientField.smooth((life-age)/1.25)
                    * AmbientField.visibility(head.x-eyeX,head.y-eyeY,head.z-eyeZ);
                if(alpha<.015) continue;
                double dx=head.x-eyeX,dy=head.y-eyeY,dz=head.z-eyeZ;
                candidates.add(new Candidate(x,y,z,lane,head,age,local,alpha,dx*dx+dy*dy+dz*dz,unit(seed)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        List<Strand> result=new ArrayList<>(cap);
        // Reserve population before frustum/terrain checks: camera turns never refill with unrelated motes.
        for(int rank=0;rank<Math.min(cap,candidates.size());rank++) {
            Candidate c=candidates.get(rank);
            double alpha=c.opacity*AmbientField.smooth((cap-rank)/(double)Math.max(4,cap/4));
            if(alpha<.015 || !visible.visible(c.head.x,c.head.y,c.head.z,3.4)) continue;
            long seed=seed(c.x,c.y,c.z,c.lane);
            double history=Math.min(c.age,1.15);
            List<Point> points=new ArrayList<>(POINTS);
            boolean open=true;
            for(int p=0;p<POINTS;p++) {
                double age=c.age-history*(1-p/(double)(POINTS-1));
                Point at=point(c.x,c.y,c.z,seed,age,c.time);
                if(!air.clear((int)Math.floor(at.x),(int)Math.floor(at.y),(int)Math.floor(at.z))) { open=false;break; }
                points.add(at);
            }
            if(open) result.add(new Strand(List.copyOf(points),alpha,c.palette));
        }
        return List.copyOf(result);
    }

    private static Point point(int x,int y,int z,long seed,double age,double birth) {
        double a=unit(mix(seed+1)),b=unit(mix(seed+2)),c=unit(mix(seed+3));
        double phase=TAU*a, t=age+birth;
        // Always inside the owning cell: conservative culling remains correct for the whole tail.
        double px=(x+.5)*AmbientField.CELL+Math.sin(t*(.60+c*.38)+phase)*1.55;
        double pz=(z+.5)*AmbientField.CELL+Math.cos(t*(.47+b*.35)+phase)*1.55;
        double py=(y+.12+.76*age/lifetime(seed))*AmbientField.CELL+Math.sin(t*.9+phase)*.24;
        return new Point(px,py,pz);
    }
    private static double lifetime(long seed) { return 8+unit(mix(seed+5))*5; }
    private static long seed(int x,int y,int z,int lane) {
        return mix(x*0x632BE59BD9B4E019L ^ y*0x9E3779B97F4A7C15L ^ z*0x94D049BB133111EBL ^ lane*0xD1B54A32D192ED03L);
    }
    private static long mix(long value) { value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;value=(value^(value>>>27))*0x94D049BB133111EBL;return value^(value>>>31); }
    private static double unit(long value) { return (value>>>11)*0x1.0p-53; }
}
