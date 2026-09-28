package dev.c4lam.celeste.module.impl.visual.expansion;

/** Exercises the shipped timing helper, pause clock and takeoff predicate directly. */
public final class JumpCircleAnimationCheck {
    private static int assertions;
    private static void require(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        require(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance, message);
    }
    private static void animation() {
        for (double duration : new double[] {.5, 1.2, 2.5}) for (double size : new double[] {.6, 1.8, 3}) {
            for (int i=0; i<=1000; i++) {
                double time=duration*i/1000;
                var stable=JumpCircleAnimation.frame(time,duration,size,false);
                var shrink=JumpCircleAnimation.frame(time,duration,size,true);
                require(stable.opacity()>=0 && stable.opacity()<=1 && stable.radius()>=0 && stable.radius()<=size, "Bounded frame");
                require(shrink.radius()<=stable.radius()+1e-12 && shrink.radius()>=0, "Shrink cannot enlarge ring");
                near(shrink.opacity(),stable.opacity(),0,"Shrink preserves fade timing");
                require(Double.isFinite(stable.rotation()),"Finite rotation");
            }
            var hold=JumpCircleAnimation.frame(duration*.5,duration,size,false);
            near(hold.radius(),size,1e-12,"Hold reaches configured radius");
            near(hold.opacity(),1,1e-12,"Hold fully visible");
            near(JumpCircleAnimation.frame(duration-.001,duration,size,false).radius(),size,1e-12,"Without Shrink size stays fixed through exit");
            require(JumpCircleAnimation.frame(duration-.001,duration,size,true).radius()<.001,"Shrink recovers smoothly to zero");
            for(double time:new double[]{-1,0,duration,duration+1,Double.NaN}) {
                var frame=JumpCircleAnimation.frame(time,duration,size,true);
                near(frame.opacity(),0,0,"No opacity outside lifetime");
                near(frame.radius(),0,0,"No geometry outside lifetime");
            }
            double previous=1;
            for(int i=0;i<200;i++) {
                double time=duration-Math.min(.2,duration*.25)+Math.min(.2,duration*.25)*i/200;
                var frame=JumpCircleAnimation.frame(time,duration,size,true);
                require(frame.opacity()<=previous+1e-12,"Exit never brightens"); previous=frame.opacity();
            }
        }
        near(JumpCircleAnimation.frame(.10,1.2,1.8,false).rotation(),Math.toRadians(100),1e-12,"Reference rotation cadence");
    }
    private static void originalAnimation() {
        for (double size : new double[] {.6, 1.8, 3}) {
            double previousRadius = 0;
            for (int i = 0; i <= 1000; i++) {
                double age = i / 1000.0;
                double radius = JumpCircleAnimation.radius(age, size);
                double alpha = JumpCircleAnimation.opacity(age);
                require(radius >= previousRadius && radius <= size, "Original ring expands smoothly");
                require(alpha >= 0 && alpha <= 1, "Original opacity bounded");
                previousRadius = radius;
            }
            near(JumpCircleAnimation.radius(0, size), size * .14, 1e-12, "Original initial ring radius restored");
            near(JumpCircleAnimation.radius(1, size), size, 1e-12, "Original final ring radius restored");
            near(JumpCircleAnimation.opacity(0), 0, 0, "Original ring starts invisible");
            near(JumpCircleAnimation.opacity(.1), 1, 1e-12, "Original ring enters before expanding fade");
            near(JumpCircleAnimation.opacity(.68), .25, 1e-12, "Original quadratic fade restored");
            near(JumpCircleAnimation.opacity(1), 0, 0, "Original ring expires cleanly");
            near(JumpCircleAnimation.opacity(-.13), 0, 0, "Delayed echo absent before its start");
        }
    }
    private static void clocks() {
        JumpCircleAnimation.Clock clock = new JumpCircleAnimation.Clock();
        near(clock.sample(5_000_000_000L, false), 0, 0, "First sample starts at zero");
        near(clock.sample(5_016_666_667L, false), .016666667, 1e-12, "Fractional frame advances");
        near(clock.sample(6_000_000_000L, false), 1, 1e-12, "One elapsed second");
        near(clock.sample(7_000_000_000L, true), 1, 1e-12, "Pause transition freezes");
        near(clock.sample(86_406_000_000_000L, true), 1, 1e-12, "Long pause does not age effects");
        near(clock.sample(86_407_000_000_000L, false), 1, 1e-12, "Resume excludes paused interval");
        near(clock.sample(86_407_016_666_667L, false), 1.016666667, 1e-12, "First subsequent frame resumes smoothly");
        clock.reset();
        near(clock.sample(100_000_000_000L, false), 0, 0, "Reset clears old elapsed time");
        for (int i = 1; i <= 10000; i++)
            near(clock.sample(100_000_000_000L + i * 1_000_000L, false), i / 1000.0, 2e-10, "Monotonic millisecond samples");
        clock.reset();
        near(clock.sample(0, true), 0, 0, "Starts paused at zero");
        near(clock.sample(100_000_000_000L, false), 0, 0, "Initial resume does not jump");
        near(clock.sample(101_000_000_000L, false), 1, 1e-12, "Runs normally after paused initialization");
        System.out.println("PASS production clock: fractional frames, 10,000 monotonic samples, 24-hour pause/resume without elapsed jump, reset and initially-paused cases");
    }
    private static void takeoff() {
        for (int bits = 0; bits < 16; bits++) {
            boolean wasGrounded = (bits & 1) != 0, grounded = (bits & 2) != 0;
            boolean jumping = (bits & 4) != 0, allowed = (bits & 8) != 0;
            require(JumpCircleAnimation.takeoff(wasGrounded, grounded, jumping, allowed, .42, .1764)
                == (wasGrounded && !grounded && jumping && allowed), "Takeoff eligibility truth table");
        }
        for (double dy : new double[] {-20, -1, -.1, 0, .025, 1, 20, Double.NaN, Double.POSITIVE_INFINITY})
            require(!JumpCircleAnimation.takeoff(true, false, true, true, dy, .2), "Falling/non-jump/vertical teleport must not trigger: " + dy);
        require(JumpCircleAnimation.takeoff(true, false, true, true, .025001, .2), "Just-above-rise threshold should trigger");
        require(JumpCircleAnimation.takeoff(true, false, true, true, .99999, 3.99999), "Valid subthreshold movement should trigger");
        for (double distance : new double[] {4, 16, Double.NaN, Double.POSITIVE_INFINITY})
            require(!JumpCircleAnimation.takeoff(true, false, true, true, .42, distance), "Teleport/nonfinite displacement must not trigger");
        for (String reason : new String[] {"dead", "spectator", "riding", "flying", "gliding", "climbing", "water", "lava"})
            require(!JumpCircleAnimation.takeoff(true, false, true, false, .42, .1764), "Not allowed: " + reason);
        System.out.println("PASS production takeoff: complete boolean eligibility table; downward/too-small/too-large rise rejected; >=2-block displacement and nonfinite input rejected");
    }
    public static void main(String[] args) {
        animation(); originalAnimation(); clocks(); takeoff();
        System.out.println("PASS " + assertions + " deterministic assertions. Feature lifecycle and game rendering require runtime verification.");
    }
}
