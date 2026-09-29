package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Bounded, world-independent hit animations. No game objects survive an accepted hit. */
public final class HitEffectsState {
    public static final int MAX_PARTICLES = 144;
    // Eight accepted targets per tick over the full ~2.1 second animation, plus headroom.
    public static final int MAX_GROUND_PULSES = 384;
    public enum Style { SPARKS, STARS, DOTS }
    public record Point(double x, double y, double z) {
        public Point add(double dx, double dy, double dz) { return new Point(x + dx, y + dy, z + dz); }
        public Point lerp(Point b, double t) { return add((b.x-x)*t, (b.y-y)*t, (b.z-z)*t); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    public record Particle(Point origin, Point velocity, double born, double life, double radius,
                           double rotation, double spin, int color, Style style) { }
    public record Sprite(Point position, Point tail, double radius, double rotation,
                         double opacity, int color, Style style) { }
    public record GroundPulse(List<HitGroundPath.Branch> branches, double born, double size, int color, int endColor) {
        public GroundPulse(List<HitGroundPath.Branch> branches,double born,double size,int color) {
            this(branches,born,size,color,color);
        }
    }
    public record Beam(Point from, Point to, double width, double opacity, int color, int endColor, double highlight, double endHighlight) {
        public Beam(Point from,Point to,double width,double opacity,int color,int endColor,double highlight) {
            this(from,to,width,opacity,color,endColor,highlight,highlight);
        }
        public Beam(Point from,Point to,double width,double opacity,int color,int endColor) {
            this(from,to,width,opacity,color,endColor,0,0);
        }
        public Beam(Point from,Point to,double width,double opacity,int color) { this(from,to,width,opacity,color,color,0,0); }
    }
    private record Edge(Point first,Point second) {
        static Edge of(Point a,Point b) {
            boolean before=a.x<b.x || a.x==b.x && (a.z<b.z || a.z==b.z && a.y<=b.y);
            return before?new Edge(a,b):new Edge(b,a);
        }
    }
    private static final class GroundEdge {
        Beam visible;
        Point firstFrom;
        double lastBorn,wave,endWave;
        GroundEdge(Beam beam,double born,double start) {
            visible=beam;firstFrom=beam.from;lastBorn=born;
        }
        void merge(Beam beam,double born,double start,double now) {
            if(born>lastBorn+.000001) {
                lastBorn=born;
                double age=now-born-start/9;
                double a=highlight(age),b=highlight(age-1.0/9);
                boolean forward=beam.from.equals(firstFrom);
                wave=Math.min(1,wave+(forward?a:b));
                endWave=Math.min(1,endWave+(forward?b:a));
            }
            if(beam.opacity>=visible.opacity)visible=beam;
        }
        Beam sample(double now) {
            boolean forward=visible.from.equals(firstFrom);
            return new Beam(visible.from,visible.to,visible.width,visible.opacity,visible.color,visible.endColor,
                forward?wave:endWave,forward?endWave:wave);
        }
        private static double highlight(double age) {return smooth(clamp(age/.035))*(1-smooth(clamp((age-.055)/.14)));}
    }
    public record Frame(List<Sprite> particles, List<Beam> ground) {
        public static final Frame EMPTY = new Frame(List.of(), List.of());
        public boolean isEmpty() { return particles.isEmpty() && ground.isEmpty(); }
    }
    private final List<Particle> particles = new ArrayList<>();
    private final List<GroundPulse> ground = new ArrayList<>();
    private long lastTick = Long.MIN_VALUE;
    private final int[] tickTargets = new int[8];
    private int targetCount;

    /** Attack hooks can run twice for one sent attack. Keep different targets independently. */
    public boolean accept(long tick, int target) {
        if (lastTick != tick) { lastTick = tick; targetCount = 0; }
        for (int i = 0; i < targetCount; i++) if (tickTargets[i] == target) return false;
        if (targetCount == tickTargets.length) return false;
        tickTargets[targetCount++] = target;
        return true;
    }

    public void burst(Point center, double now, int amount, double size, int color, Style style, long seed) {
        if (center == null || !center.finite() || !Double.isFinite(now)) return;
        int count = Math.max(4, Math.min(24, amount));
        double scale = finite(size, 1, .5, 2);
        Random random = new Random(seed);
        while (particles.size() > MAX_PARTICLES - count) particles.removeFirst();
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double speed = .8 + random.nextDouble() * 1.7;
            Point origin = center.add((random.nextDouble()-.5)*.22,
                (random.nextDouble()-.5)*.28, (random.nextDouble()-.5)*.22);
            Point velocity = new Point(Math.cos(angle)*speed, .55+random.nextDouble()*1.35, Math.sin(angle)*speed);
            particles.add(new Particle(origin, velocity, now, 1.0+random.nextDouble()*.55,
                (.045+random.nextDouble()*.035)*scale, angle,
                (random.nextBoolean() ? 1 : -1)*(1.5+random.nextDouble()*3), color & 0xFFFFFF,
                style == null ? Style.SPARKS : style));
        }
    }

    public void ground(List<HitGroundPath.Branch> branches, double now, double size, int color) {
        ground(branches,now,size,color,color);
    }

    public void ground(List<HitGroundPath.Branch> branches, double now, double size, int color,int endColor) {
        if (branches == null || branches.isEmpty() || !Double.isFinite(now)) return;
        expireGround(now);
        // Never replace a still-running hit. The safety cap admits the bounded normal attack rate.
        if (ground.size() >= MAX_GROUND_PULSES) return;
        ground.add(new GroundPulse(List.copyOf(branches), now, finite(size,1,.5,2), color & 0xFFFFFF,endColor & 0xFFFFFF));
    }

    public Frame frame(double now) {
        if (!Double.isFinite(now)) return Frame.EMPTY;
        particles.removeIf(p -> now < p.born || now - p.born >= p.life);
        expireGround(now);
        if (particles.isEmpty() && ground.isEmpty()) return Frame.EMPTY;
        List<Sprite> sprites = new ArrayList<>(particles.size());
        for (Particle p : particles) {
            double age = now-p.born, alpha = opacity(age,p.life);
            if (alpha < .002) continue;
            sprites.add(new Sprite(position(p,age), position(p,Math.max(0,age-.07)),
                p.radius*(.55+.45*Math.sqrt(alpha)), p.rotation+p.spin*age, alpha,p.color,p.style));
        }
        Map<Edge,GroundEdge> beams = new LinkedHashMap<>();
        for (GroundPulse p : ground) for (HitGroundPath.Branch branch:p.branches) {
            double age = now-p.born;
            if (age <= 0) continue;
            for (HitGroundPath.Segment s : branch.segments()) {
                // A whole block fades together. Neither end crawls through fractional block positions.
                double revealed = age-s.start()/9;
                double alpha = smooth(clamp(revealed/.075))*(1-smooth(clamp((revealed-.30)/.62)));
                if (alpha < .002) continue;
                int start=gradient(p.color,p.endColor,s.start()/HitGroundPath.STEPS);
                int end=gradient(p.color,p.endColor,s.end()/HitGroundPath.STEPS);
                Beam beam=new Beam(s.from(),s.to(),.048*p.size,alpha,start,end);
                Edge key=Edge.of(s.from(),s.to());
                GroundEdge previous=beams.get(key);
                // Shared prefixes draw once. A later hit sends a separate short highlight out along overlap.
                if(previous==null)beams.put(key,new GroundEdge(beam,p.born,s.start()));
                else previous.merge(beam,p.born,s.start(),now);
            }
        }
        return new Frame(List.copyOf(sprites),beams.values().stream().map(edge->edge.sample(now)).toList());
    }

    public static int gradient(int start,int end,double progress) {
        double t=clamp(progress);int result=0;
        for(int shift=0;shift<=16;shift+=8) {
            int a=start>>shift&255,b=end>>shift&255;
            result|=(int)Math.round(a+(b-a)*t)<<shift;
        }
        return result;
    }

    private void expireGround(double now) {
        ground.removeIf(p -> now < p.born || now-p.born >= HitGroundPath.STEPS/9.0+.95);
    }

    public void clearParticles() { particles.clear(); }
    public void clearGround() { ground.clear(); }
    public void clear() { particles.clear(); ground.clear(); lastTick=Long.MIN_VALUE; targetCount=0; }
    public int particleCount() { return particles.size(); }
    public int groundCount() { return ground.size(); }
    public static Point position(Particle p, double age) {
        double t = Math.max(0,Math.min(p.life,age));
        double travel = (1-Math.exp(-2.6*t))/2.6;
        return p.origin.add(p.velocity.x*travel,p.velocity.y*travel-.64*t*t,p.velocity.z*travel);
    }
    public static double opacity(double age, double life) {
        if (age <= 0 || age >= life || life <= 0) return 0;
        return smooth(clamp(age/.055))*(1-smooth(clamp((age/life-.35)/.65)));
    }
    private static double smooth(double t) { return t*t*(3-2*t); }
    private static double clamp(double t) { return Math.max(0,Math.min(1,t)); }
    static double finite(double value,double fallback,double low,double high) {
        return Double.isFinite(value) ? Math.max(low,Math.min(high,value)) : fallback;
    }
    public static final class Clock {
        private long last = Long.MIN_VALUE;
        private double time;
        public double sample(long nanos, boolean paused) {
            if (last != Long.MIN_VALUE && nanos > last && !paused)
                time += Math.min(.25,(nanos-last)*1e-9);
            last=nanos;
            return time;
        }
        public void clear() { last=Long.MIN_VALUE; time=0; }
    }
}
