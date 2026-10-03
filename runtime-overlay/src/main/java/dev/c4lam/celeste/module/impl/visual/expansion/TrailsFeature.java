package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** Soft, fading light behind the local player's actual movement. */
public final class TrailsFeature implements ModuleFeature {
    private final PlayerTrailHistory history = new PlayerTrailHistory();
    private final PlayerTrailHistory.Clock clock = new PlayerTrailHistory.Clock();
    private volatile boolean enabled, firstPerson;
    private volatile long lifetimeMs = 1500;
    private volatile double size = .20, opacity = .8;
    private volatile String theme = "Celeste";
    private volatile int color = 0xFF4FA3;

    @Override public String name() { return "Trails"; }
    @Override public void onEnable(ModuleContext context) { clear(); update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; clear(); }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; clear(); }

    public void extract(WorldExtractionContext context, List<TrailsWorldGlowRenderer.Quad> glows, int quadLimit) {
        var client = MinecraftClient.getInstance();
        if (!enabled || context == null || context.camera() == null || client.player == null
            || client.world != context.world() || !client.player.isAlive() || client.player.isSpectator()
            || !firstPerson && client.options.getPerspective().isFirstPerson()) {
            clear();
            return;
        }
        float delta = context.tickCounter().getTickProgress(false);
        Vec3d current = client.player.getLerpedPos(delta).add(0, client.player.getHeight() * .48, 0);
        long now = clock.update(System.nanoTime() / 1_000_000L, client.isPaused());
        List<PlayerTrailHistory.Sample> samples = history.observe(context.world(), client.player,
            new PlayerTrailHistory.Point(current.x, current.y, current.z), now, lifetimeMs);
        if (glows == null || samples.size() < 2) return;
        int available = Math.max(0, Math.min(768, quadLimit) - glows.size());
        if (available == 0) return;
        List<Vec3d> points = new ArrayList<>(samples.size());
        double[] distances = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            var p = samples.get(i).point();
            points.add(new Vec3d(p.x(), p.y(), p.z()));
            if (i > 0) distances[i] = distances[i - 1] + points.get(i).distanceTo(points.get(i - 1));
        }
        double total = distances[distances.length - 1];
        TrailsGlowTrail.appendRound(glows, points, context.camera().getCameraPos(), Math.min(64, available),
            1, opacity, false, progress -> TrailsVisualEffects.color(theme, color, 0, progress * .32),
            progress -> {
                double at = total * progress;
                int edge = 1;
                while (edge < distances.length - 1 && distances[edge] < at) edge++;
                double span = distances[edge] - distances[edge - 1];
                double t = span < 1e-8 ? 1 : Math.max(0, Math.min(1, (at - distances[edge - 1]) / span));
                double a = PlayerTrailHistory.alpha(samples.get(edge - 1), now, lifetimeMs);
                double b = PlayerTrailHistory.alpha(samples.get(edge), now, lifetimeMs);
                return a + (b - a) * t;
            }, size);
    }

    private void update(ModuleContext context) {
        lifetimeMs = (long) (finite(context.slider("Length", 1.5), 1.5, .5, 3) * 1000);
        size = finite(context.slider("Size", .20), .20, .05, .45);
        opacity = finite(context.slider("Opacity", .8), .8, .1, 1);
        firstPerson = context.toggle("First person", false);
        theme = context.mode("Theme", "Celeste");
        color = context.colorPick("Color", 0xFF4FA3) & 0xFFFFFF;
    }
    private void clear() { history.clear(); clock.clear(); }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
}
