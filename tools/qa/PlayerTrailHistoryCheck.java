import dev.c4lam.celeste.module.impl.visual.expansion.PlayerTrailHistory;
import dev.c4lam.celeste.module.impl.visual.expansion.PlayerTrailHistory.Point;

/** Production movement/lifetime history; no game world or renderer is mocked. */
public final class PlayerTrailHistoryCheck {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PlayerTrailHistory trail = new PlayerTrailHistory();
        Object world = new Object(), player = new Object();
        var start = trail.observe(world, player, new Point(0, 1, 0), 0, 1500);
        var moving = trail.observe(world, player, new Point(.1, 1, 0), 10, 1500);
        check(moving.size() == 2 && moving.getLast().point().x() == .1, "Interpolated head should move before the next sample interval");
        check(start.size() == 1, "Published snapshot mutated");
        for (int tick = 1; tick <= 500; tick++) {
            var samples = trail.observe(world, player, new Point(tick * .02, 1, 0), 10 + tick * 10L, 3000);
            check(samples.size() <= PlayerTrailHistory.MAX_SAMPLES, "Unbounded sample history");
            check(samples.getLast().point().x() == tick * .02, "Render head lags behind current position");
        }
        Point stopped = new Point(10, 1, 0);
        var beforeStop = trail.observe(world, player, stopped, 5010, 1500);
        var fading = trail.observe(world, player, stopped, 6300, 1500);
        check(!fading.isEmpty(), "Trail disappeared before its lifetime");
        check(PlayerTrailHistory.alpha(fading.getLast(), 6300, 1500) < 1, "Standing still refreshed the trail forever");
        check(trail.observe(world, player, stopped, 6600, 1500).isEmpty(), "Stationary trail did not expire");
        var resumed = trail.observe(world, player, new Point(10.1, 1, 0), 6610, 1500);
        check(resumed.size() == 1, "Resuming movement bridged expired history");
        var teleported = trail.observe(world, player, new Point(200, 1, 0), 6650, 1500);
        check(teleported.size() == 1, "Trail crossed a teleport");
        check(trail.observe(new Object(), player, new Point(200.1, 1, 0), 6700, 1500).size() == 1,
            "Trail crossed a world change");
        check(trail.observe(world, new Object(), new Point(200.2, 1, 0), 6750, 1500).size() == 1,
            "Trail crossed a player replacement");
        check(trail.observe(world, player, new Point(200.3, 1, 0), 10, 1500).size() == 1,
            "Clock regression retained old history");
        check(trail.observe(world, player, new Point(Double.NaN, 0, 0), 20, 1500).isEmpty(), "NaN position accepted");
        check(trail.observe(null, player, stopped, 25, 1500).isEmpty(), "Disconnected state retained history");
        trail.clear();
        check(trail.observe(world, player, stopped, 30, 1500).size() == 1, "Disable/reset retained history");
        var sample = beforeStop.getLast();
        double previous = 1;
        for (int age = 0; age <= 1600; age += 10) {
            double alpha = PlayerTrailHistory.alpha(sample, sample.timeMs() + age, 1500);
            check(alpha >= 0 && alpha <= previous, "Fade is non-monotonic or negative");
            previous = alpha;
        }
        check(previous == 0, "Expired trail retained opacity");
        for (long lifetime : new long[] {500, 1500, 3000}) {
            check(PlayerTrailHistory.alpha(sample, sample.timeMs() + lifetime - 250, lifetime) == 1,
                "Trail should keep its glow until the final 250 ms");
            check(Math.abs(PlayerTrailHistory.alpha(sample, sample.timeMs() + lifetime - 125, lifetime) - .5) < 1e-12,
                "Last 250 ms should provide a smooth half-opacity midpoint");
            check(PlayerTrailHistory.alpha(sample, sample.timeMs() + lifetime, lifetime) == 0,
                "Trail must be invisible exactly at expiry");
        }
        var clock = new PlayerTrailHistory.Clock();
        check(clock.update(100, false) == 0 && clock.update(150, false) == 50, "Active trail clock did not advance");
        check(clock.update(200, true) == 50 && clock.update(5200, true) == 50, "Paused trail continued aging");
        check(clock.update(10200, false) == 50, "First resumed frame counted the paused gap");
        check(clock.update(10250, false) == 100, "Resumed trail clock did not advance");
        clock.clear();
        check(clock.update(10300, false) == 0, "Clock reset retained previous lifetime");
        System.out.println("Player trails: " + checks + " checks passed");
    }
}
