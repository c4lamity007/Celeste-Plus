package dev.c4lam.celeste.module.impl.combat;

/** Bounded, allocation-light air prediction for Java 1.21.11; launch spread is deliberately omitted. */
public final class HorizontalPearlTrajectory {
    public static final double SPEED = 1.5;
    public static final double DRAG = (double) 0.99F;
    public static final double GRAVITY = 0.03;
    public static final double PEARL_EYE_OFFSET = (double) 0.1F;
    public static final int MAX_FLIGHT_TICKS = 48;
    public static final int MAX_DELAYED_THROW_TICKS = 30;
    // Wind charge dimensions: .3125 x .3125, box bottom is entity Y - .15F.
    private static final double HALF = 0.15625;
    private static final Vec CENTER_OFFSET = new Vec(0, HALF - (double) 0.15F, 0);

    private HorizontalPearlTrajectory() { }

    public record Vec(double x, double y, double z) {
        public Vec add(Vec v) { return new Vec(x + v.x, y + v.y, z + v.z); }
        public Vec subtract(Vec v) { return new Vec(x - v.x, y - v.y, z - v.z); }
        public Vec scale(double s) { return new Vec(x * s, y * s, z * s); }
        public double dot(Vec v) { return x * v.x + y * v.y + z * v.z; }
        public double length() { return Math.sqrt(dot(this)); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }

    public record State(Vec position, Vec velocity) {
        public boolean valid() { return position != null && velocity != null && position.finite() && velocity.finite(); }
    }

    public record Solution(float yaw, float pitch, Vec windVelocity, int windTicks, double pearlFraction,
                           Vec catchPosition) { }

    public static Vec inherited(Vec movement, boolean onGround) {
        return new Vec(movement.x, onGround ? 0 : movement.y, movement.z);
    }

    public static Vec direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch), horizontal = Math.cos(p);
        return new Vec(-Math.sin(y) * horizontal, -Math.sin(p), Math.cos(y) * horizontal);
    }

    public static State launch(Vec eyes, Vec movement, boolean onGround, float yaw, float pitch) {
        return new State(eyes.add(new Vec(0, -PEARL_EYE_OFFSET, 0)),
            direction(yaw, pitch).scale(SPEED).add(inherited(movement, onGround)));
    }

    public static State step(State pearl) {
        // ThrownEntity.tick applies gravity, then drag, THEN moves in 1.21.11.
        Vec velocity = pearl.velocity.add(new Vec(0, -GRAVITY, 0)).scale(DRAG);
        return new State(pearl.position.add(velocity), velocity);
    }

    public static State advance(State pearl, int ticks) {
        if (ticks < 0 || ticks > MAX_FLIGHT_TICKS + 2) throw new IllegalArgumentException("Invalid pearl age");
        for (int tick = 0; tick < ticks; tick++) pearl = step(pearl);
        return pearl;
    }

    /** One unobstructed 1.21.11 elytra travel step; prediction only, never applied to the player. */
    public static Vec glideVelocity(Vec movement, float yaw, float pitch, double gravity, boolean slowFalling) {
        if (movement == null || !movement.finite() || !Float.isFinite(yaw) || !Float.isFinite(pitch)
            || !Double.isFinite(gravity) || gravity < 0) return null;
        // Elytra travel uses Entity's float/sine-table look, not a double-trig projectile ray.
        // At exactly -90 degrees the horizontal look is zero, so vanilla skips climb lift.
        float pitchRadians = pitch * .017453292F;
        float yawRadians = -yaw * .017453292F;
        float cosPitch = glideTrig(pitchRadians, true);
        Vec look = new Vec(glideTrig(yawRadians, false) * cosPitch,
            -glideTrig(pitchRadians, false), glideTrig(yawRadians, true) * cosPitch);
        double horizontalLook = Math.hypot(look.x, look.z);
        double horizontalSpeed = Math.hypot(movement.x, movement.z);
        double radians = pitchRadians;
        double lift = Math.cos(radians);
        lift *= lift;
        if (slowFalling && movement.y <= 0) gravity = Math.min(gravity, .01);
        Vec next = movement.add(new Vec(0, gravity * (-1 + lift * .75), 0));
        if (next.y < 0 && horizontalLook > 0) {
            double rise = next.y * -.1 * lift;
            next = next.add(new Vec(look.x / horizontalLook * rise, rise, look.z / horizontalLook * rise));
        }
        if (radians < 0 && horizontalLook > 0) {
            double rise = horizontalSpeed * -glideTrig(radians, false) * .04;
            next = next.add(new Vec(-look.x / horizontalLook * rise, rise * 3.2, -look.z / horizontalLook * rise));
        }
        if (horizontalLook > 0) {
            next = next.add(new Vec((look.x / horizontalLook * horizontalSpeed - next.x) * .1, 0,
                (look.z / horizontalLook * horizontalSpeed - next.z) * .1));
        }
        return new Vec(next.x * (double) .99F, next.y * (double) .98F, next.z * (double) .99F);
    }

    private static float glideTrig(double angle, boolean cosine) {
        // Equivalent to 1.21.11 MathHelper's lookup, without allocating a 65,536-entry table.
        double scale = 10430.378350470453;
        long index = (long) (angle * scale + (cosine ? 16384 : 0)) & 65535L;
        return (float) Math.sin(index / scale);
    }

    /**
     * Vanilla pearls cannot be hit by wind charges. The catch occurs when the older pearl's
     * movement ray hits the younger wind charge's box. Search that discrete tick order, not
     * merely two simultaneous points. A wind charge has zero acceleration and drag 1.
     */
    public static Solution solve(State thrownPearl, int throwDelay, Vec windOrigin, Vec windInherited) {
        if (!throwWindow(throwDelay)) return null;
        return solveDelayed(thrownPearl, throwDelay, windOrigin, windInherited);
    }

    /** A straight-up pearl keeps its launch yaw throughout the catch, including tiny input rounding. */
    public static Solution solve(State thrownPearl, int throwDelay, Vec windOrigin, Vec windInherited,
                                 float pearlYaw, float pearlPitch) {
        if (!Float.isFinite(pearlYaw) || !Float.isFinite(pearlPitch) || !throwWindow(throwDelay)) return null;
        if (pearlPitch < -90 || pearlPitch > -89.99F) return solve(thrownPearl, throwDelay, windOrigin, windInherited);
        return solveDelayed(thrownPearl, throwDelay, windOrigin, windInherited, pearlYaw);
    }

    /** The upward Catch macro has a configurable wait; horizontal catches still use solve's 1–2 ticks. */
    public static Solution solveDelayed(State thrownPearl, int throwDelay, Vec windOrigin, Vec windInherited) {
        return solveDelayed(thrownPearl, throwDelay, windOrigin, windInherited, Float.NaN);
    }

    private static Solution solveDelayed(State thrownPearl, int throwDelay, Vec windOrigin, Vec windInherited,
                                         float fixedYaw) {
        if (thrownPearl == null || !thrownPearl.valid() || throwDelay < 1 || throwDelay > MAX_DELAYED_THROW_TICKS
            || windOrigin == null || windInherited == null || !windOrigin.finite() || !windInherited.finite()) return null;
        if (Float.isFinite(fixedYaw)) {
            // Yaw is undefined on a vertical ray. Prefer that ray over sideways corrections caused
            // by coordinate rounding or drag, but accept it only when it really catches the pearl.
            Solution vertical = aimedCollision(thrownPearl, throwDelay, windOrigin, windInherited,
                MAX_FLIGHT_TICKS + 1, fixedYaw, -90);
            if (vertical != null) return vertical;
        }
        State pearl = advance(thrownPearl, throwDelay);
        // At the first pearl tick after charge spawn, the charge has not moved yet.
        pearl = step(pearl);
        for (int windTicks = 1; windTicks <= MAX_FLIGHT_TICKS; windTicks++) {
            State next = step(pearl);
            Vec a = pearl.position.subtract(windOrigin).subtract(CENTER_OFFSET).subtract(windInherited.scale(windTicks));
            Vec segment = next.position.subtract(pearl.position);
            double radius = SPEED * windTicks;
            double aa = segment.dot(segment), bb = 2 * a.dot(segment), cc = a.dot(a) - radius * radius;
            double discriminant = bb * bb - 4 * aa * cc;
            // Solve |a + f * segment| = speed * windTicks for f within this pearl tick.
            if (aa > 1e-12 && discriminant >= 0) {
                double root = Math.sqrt(discriminant);
                for (double fraction : new double[] {(-bb - root) / (2 * aa), (-bb + root) / (2 * aa)}) {
                    if (fraction < 0 || fraction > 1) continue;
                    Solution result = candidate(thrownPearl, throwDelay, windOrigin, windInherited,
                        windTicks, a.add(segment.scale(fraction)), fixedYaw);
                    if (result != null) return result;
                }
            }
            // The hitbox has width: near misses of its centre may still be an exact box hit.
            for (double fraction : new double[] {.5, 0, 1}) {
                Vec displacement = a.add(segment.scale(fraction));
                if (Math.abs(displacement.length() - radius) > HALF + .3) continue;
                Solution result = candidate(thrownPearl, throwDelay, windOrigin, windInherited, windTicks, displacement, fixedYaw);
                if (result != null) return result;
            }
            pearl = next;
        }
        return null;
    }

    private static Solution candidate(State pearl, int delay, Vec origin, Vec inherited, int ticks, Vec aim, float fixedYaw) {
        double length = aim.length();
        if (length < 1e-9 || !Double.isFinite(length)) return null;
        float yaw = Float.isFinite(fixedYaw) ? fixedYaw : (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        double horizontal = Float.isFinite(fixedYaw)
            ? Math.max(0, aim.dot(direction(fixedYaw, 0))) : Math.hypot(aim.x, aim.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, horizontal));
        return aimedCollision(pearl, delay, origin, inherited, ticks + 1, yaw, pitch);
    }

    private static Solution aimedCollision(State pearl, int delay, Vec origin, Vec inherited, int limit,
                                            float yaw, float pitch) {
        Vec velocity = direction(yaw, pitch).scale(SPEED).add(inherited);
        Solution hit = firstCollision(pearl, delay, origin, velocity, limit);
        return hit == null ? null : new Solution(yaw, pitch, velocity, hit.windTicks, hit.pearlFraction, hit.catchPosition);
    }

    /** Replays the pearl's collision rays against the charge before that charge's next movement. */
    public static Solution firstCollision(State pearl, int delay, Vec origin, Vec velocity, int limit) {
        pearl = advance(pearl, delay);
        for (int age = 0; age <= Math.min(limit, MAX_FLIGHT_TICKS + 1); age++) {
            State next = step(pearl);
            Vec center = origin.add(velocity.scale(age)).add(CENTER_OFFSET);
            // ProjectileUtil.getToleranceMargin grows with the PEARL's age (not the charge's).
            double margin = Math.max(0, Math.min(.3F, (float) (delay + age - 2) / 20F));
            double fraction = rayBox(pearl.position, next.position, center, HALF + margin);
            if (fraction >= 0) return new Solution(0, 0, velocity, age, fraction,
                pearl.position.add(next.position.subtract(pearl.position).scale(fraction)));
            pearl = next;
        }
        return null;
    }

    static double rayBox(Vec from, Vec to, Vec center, double half) {
        Vec p = from.subtract(center), d = to.subtract(from);
        double best = 1;
        double[] positions = {p.x, p.y, p.z}, directions = {d.x, d.y, d.z};
        // Box.raycast tests strict entrance faces, NOT a generic slab overlap.
        // Starting inside/on the box or touching only at the segment end is not a hit.
        for (int axis = 0; axis < 3; axis++) {
            double position = positions[axis], direction = directions[axis];
            if (Math.abs(direction) <= 1e-7) continue;
            double entry = ((direction > 0 ? -half : half) - position) / direction;
            if (entry <= 0 || entry >= best) continue;
            boolean insideFace = true;
            for (int other = 0; other < 3; other++) {
                if (axis == other) continue;
                double intersection = positions[other] + entry * directions[other];
                if (intersection <= -half - 1e-7 || intersection >= half + 1e-7) insideFace = false;
            }
            if (insideFace) best = entry;
        }
        return best < 1 ? best : -1;
    }

    public static boolean throwWindow(long elapsedTicks) { return elapsedTicks >= 1 && elapsedTicks <= 2; }
}
