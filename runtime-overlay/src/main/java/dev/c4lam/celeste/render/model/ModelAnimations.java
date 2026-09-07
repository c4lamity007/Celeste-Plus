package dev.c4lam.celeste.render.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGenerator;

/** Frame-time playback. A celebration never moves the real player or loops forever. */
public final class ModelAnimations {
    private final GlbModel model;
    private final int idle;
    private final int walk;
    private final List<Integer> celebrations;
    private int celebration = -1;
    private double started;
    private double previousFrame = Double.NaN;

    public ModelAnimations(GlbModel model) {
        this.model = model;
        int standing = -1, moving = -1;
        List<Integer> choices = new ArrayList<>();
        for (int i = 0; i < model.animations().size(); i++) {
            var clip = model.animations().get(i);
            if (clip.duration() <= 0) continue;
            String name = clip.name().toLowerCase(Locale.ROOT);
            if (name.contains("idle") || name.contains("stand")) { if (standing < 0) standing = i; }
            else if (name.contains("dance") || name.contains("celebration")) choices.add(i);
            else if (name.contains("walk") || name.contains("run")) { if (moving < 0) moving = i; }
            else if (!name.contains("death") && !name.contains("die") && !name.contains("fall")
                && !name.contains("jump") && !name.contains("t-pose") && !name.contains("bind")) choices.add(i);
        }
        idle = standing;
        walk = moving;
        celebrations = List.copyOf(choices);
    }

    public void celebrate(double now, boolean stationary, RandomGenerator random) {
        if (!stationary || !Double.isFinite(now) || celebrations.isEmpty()) return;
        celebration = celebrations.get(random.nextInt(celebrations.size()));
        started = now;
    }

    public Playback frame(double now, boolean stationary, boolean celebrationsEnabled) {
        if (!Double.isFinite(now)) { clear(); previousFrame = Double.NaN; return new Playback(-1, 0, false); }
        if (!stationary || !celebrationsEnabled || now < started || now < previousFrame) clear();
        previousFrame = now;
        if (celebration >= 0) {
            double time = now - started;
            if (time < Math.min(20, model.animations().get(celebration).duration()))
                return new Playback(celebration, time, false);
            clear();
        }
        return new Playback(stationary || walk < 0 ? idle : walk, now, true);
    }

    public void clear() { celebration = -1; }
    public record Playback(int clip, double seconds, boolean loop) {}
}
