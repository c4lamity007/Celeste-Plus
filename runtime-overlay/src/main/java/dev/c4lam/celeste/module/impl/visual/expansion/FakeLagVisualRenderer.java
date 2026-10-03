package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.render.geometry.WorldLineStrip;
import dev.c4lam.celeste.render.geometry.WorldBox;
import dev.c4lam.celeste.module.impl.combat.FakeLagFeature;
import dev.c4lam.celeste.module.impl.combat.FakeLagFeature.VisualSettings;
import dev.c4lam.celeste.module.impl.combat.FakeLagFeature.VisualSnapshot;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decorative projection of successful writes, never server acknowledgements or gameplay state. */
public final class FakeLagVisualRenderer {
    private static final double TAU = Math.PI * 2D;
    private static final int MAX_GLOW_QUADS = 192;
    private static final int MAX_TRAIL_POINTS = 48;
    private static final FakeLagPlayerEchoRenderer PLAYER_ECHOES = new FakeLagPlayerEchoRenderer();
    private static final float OPACITY = 0.75F;
    private static final float GLOW_GAIN = 1.55F;
    private static Object shownWorld;
    private static float visibility;
    private static long lastFrameNanos;
    private static Vec3d shownPosition;
    private static Vec3d previousObservedPosition;
    private static double shownYaw;

    private FakeLagVisualRenderer() { }

    static FakeLagPlayerEchoRenderer.Frame extract(WorldExtractionContext context, List<WorldBox> boxes,
                        List<WorldLineStrip> lines,
                        List<FakeLagWorldGlowRenderer.Quad> glows) {
        return extract(context, boxes, lines, glows, 768);
    }

    static FakeLagPlayerEchoRenderer.Frame extract(WorldExtractionContext context, List<WorldBox> boxes,
                        List<WorldLineStrip> lines,
                        List<FakeLagWorldGlowRenderer.Quad> glows, int quadLimit) {
        MinecraftClient client = MinecraftClient.getInstance();
        VisualSnapshot snapshot = FakeLagFeature.visualSnapshot();
        long now = System.nanoTime();
        if (!snapshot.active() || snapshot.world() != context.world() || client.player == null) {
            clearAnimation();
            return FakeLagPlayerEchoRenderer.Frame.EMPTY;
        }
        if (shownWorld != snapshot.world()) {
            clearAnimation();
            shownWorld = snapshot.world();
        }
        VisualSettings settings = snapshot.settings();
        if ("Off".equalsIgnoreCase(settings.style())) {
            clearAnimation();
            return FakeLagPlayerEchoRenderer.Frame.EMPTY;
        }
        // Starting at zero elapsed time avoids favoring a particular frame rate.
        double dt = lastFrameNanos == 0L ? 0D : Math.max(0D, (now - lastFrameNanos) / 1_000_000_000D);
        lastFrameNanos = now;
        Vec3d observed = snapshot.lastSent();
        Vec3d local = client.player.getLerpedPos(context.tickCounter().getTickProgress(false));
        if (observed != null) {
            boolean snap = shownPosition == null || previousObservedPosition == null
                || FakeLagAnimation.shouldSnap(previousObservedPosition.squaredDistanceTo(observed))
                || FakeLagAnimation.shouldSnap(shownPosition.squaredDistanceTo(observed));
            if (snap) {
                shownPosition = observed;
                shownYaw = snapshot.lastSentYaw();
            } else {
                shownPosition = shownPosition.lerp(observed, FakeLagAnimation.factor(dt, 20D));
                shownYaw = FakeLagAnimation.followYaw(shownYaw, snapshot.lastSentYaw(), dt);
            }
            previousObservedPosition = observed;
        } else {
            shownPosition = null;
            previousObservedPosition = null;
        }
        double separation = observed == null ? 0D : observed.distanceTo(local);
        float target = snapshot.queuedPackets() > 0 && observed != null && separation > 0.08D ? 1F : 0F;
        visibility = (float) FakeLagAnimation.follow(visibility, target, dt, target > visibility ? 12D : 7D);
        float alpha = visibility * OPACITY;
        int color = settings.color() & 0xFFFFFF;
        int accent = mix(color, ((color & 255) << 16) | ((color >> 16 & 255) << 8) | (color >> 8 & 255), .32F);
        double clock = now / 1_000_000_000D;
        Vector3f right = context.camera().getRotation().transform(new Vector3f(1F, 0F, 0F));
        Vector3f up = context.camera().getRotation().transform(new Vector3f(0F, 1F, 0F));
        GlowFrame glow = new GlowFrame(glows, glows.size(), Math.min(768, Math.max(0, quadLimit)), context.camera().getCameraPos(),
            new Vec3d(right.x, right.y, right.z), new Vec3d(up.x, up.y, up.z));
        boolean showPlayer = !"box".equalsIgnoreCase(settings.style()) && !"rings".equalsIgnoreCase(settings.style());
        FakeLagPlayerEchoRenderer.Frame echoes = FakeLagPlayerEchoRenderer.Frame.EMPTY;
        // Capture poses even while opacity is zero so the first visible echo has history.
        if (showPlayer && shownPosition != null) {
            echoes = PLAYER_ECHOES.extract(context, shownPosition, (float) shownYaw,
                new FakeLagPlayerEchoRenderer.Settings(100, 2500, color, accent, alpha, settings.trail()));
        } else {
            PLAYER_ECHOES.clear();
        }
        if (shownPosition != null && alpha > .008F && separation < 256D) {
            Vec3d sent = shownPosition;
            float height = client.player.getHeight();
            foundation(sent, alpha, color, accent, clock, glow);
            switch (settings.style().toLowerCase(Locale.ROOT)) {
                case "box" -> box(sent, height, alpha, color, accent, boxes, lines, glow, clock);
                case "rings" -> rings(sent, height, alpha, color, accent, lines, glow, clock);
                default -> energyOrbit(sent, height, alpha, color, accent, glow, clock);
            }
            if (settings.trail()) trail(snapshot, sent, local, alpha, color, accent, lines, glow, clock);
            if (settings.tether()) tether(sent, local, alpha, color, accent, lines, glow, clock);
        }
        if (snapshot.releasePosition() != null) {
            double progress = (now - snapshot.releaseNanos()) / 700_000_000D;
            if (progress >= 0D && progress < 1D) {
                float pulse = OPACITY * (float) Math.pow(1D - progress, 2D);
                double radius = .35D + .95D * (1D - Math.pow(1D - progress, 3D));
                ground(glow, FakeLagWorldGlowRenderer.Texture.PULSE, snapshot.releasePosition().add(0D, .035D, 0D),
                    radius, 0D, mix(color, 0xFFFFFF, .35F), pulse * .40F);
                ground(glow, FakeLagWorldGlowRenderer.Texture.SOFT, snapshot.releasePosition().add(0D, .025D, 0D),
                    radius * .68D, 0D, accent, pulse * .08F);
            }
        }
        return echoes;
    }

    private static void foundation(Vec3d position, float alpha, int color, int accent, double clock, GlowFrame glow) {
        double radius = .61D + Math.sin(clock * 1.8D) * .018D;
        ground(glow, FakeLagWorldGlowRenderer.Texture.SOFT, position.add(0D, .015D, 0D), .78D, 0D, color, alpha * .12F);
        ground(glow, FakeLagWorldGlowRenderer.Texture.PULSE, position.add(0D, .027D, 0D), radius, clock * .25D, color, alpha * .54F);
        ground(glow, FakeLagWorldGlowRenderer.Texture.HALO, position.add(0D, .034D, 0D), radius * .83D, -clock * .31D,
            accent, alpha * .13F);
    }

    private static void energyOrbit(Vec3d center, float height, float alpha, int color, int accent,
                                    GlowFrame frame, double clock) {
        List<FakeLagConnectedRibbon.Node> nodes = new ArrayList<>(49);
        for (int i = 0; i <= 48; i++) {
            double p = i / 48D, angle = clock * .45D + p * TAU;
            Vec3d point = center.add(Math.cos(angle) * .51D,
                height * (.46D + Math.sin(angle) * .15D), Math.sin(angle) * .51D);
            int rgb = mix(color, accent, (float) (.5D - Math.cos(p * TAU) * .5D));
            nodes.add(new FakeLagConnectedRibbon.Node(point, .23D, argb(alpha * .50F, rgb)));
        }
        connected(frame, nodes, new Vec3d(0D, 1D, 0D), 64, true);
    }

    private static void box(Vec3d position, float height, float alpha, int color, int accent,
                            List<WorldBox> boxes,
                            List<WorldLineStrip> lines, GlowFrame glow, double clock) {
        Box bounds = new Box(position.x - .31D, position.y + .01D, position.z - .31D,
            position.x + .31D, position.y + height + .02D, position.z + .31D);
        boxes.add(new WorldBox(bounds, argb(alpha * .025F, color),
            argb(alpha * .30F, color), 1.05F));
        for (int x = -1; x <= 1; x += 2) {
            for (int z = -1; z <= 1; z += 2) {
                Vec3d foot = position.add(x * .31D, .025D, z * .31D);
                Vec3d head = foot.add(0D, height, 0D);
                streak(glow, foot, head, .13D, color, alpha * .24F);
                line(List.of(foot.add(-x * .17D, 0D, 0D), foot, foot.add(0D, .22D, 0D)),
                    color, alpha * .9F, 1.6F, lines);
                line(List.of(head.add(-x * .17D, 0D, 0D), head, head.add(0D, -.22D, 0D)),
                    mix(color, 0xFFFFFF, .28F), alpha * .85F, 1.5F, lines);
            }
        }
        double scan = (.5D + Math.sin(clock * 2D) * .5D) * height;
        Vec3d scanCenter = position.add(0D, scan, 0D);
        ground(glow, FakeLagWorldGlowRenderer.Texture.SOFT, scanCenter, .49D, 0D, color, alpha * .19F);
        for (int z = -1; z <= 1; z += 2) {
            Vec3d a = scanCenter.add(-.31D, 0D, z * .31D);
            Vec3d b = scanCenter.add(.31D, 0D, z * .31D);
            streak(glow, a, b, .13D, accent, alpha * .48F);
            line(List.of(a, b), mix(color, 0xFFFFFF, .30F), alpha * .78F, 1.35F, lines);
        }
    }

    private static void rings(Vec3d position, float height, float alpha, int color, int accent,
                              List<WorldLineStrip> lines, GlowFrame glow, double clock) {
        for (int layer = 0; layer < 3; layer++) {
            double y = .05D + height * (.2D + layer * .27D);
            double phase = clock * (layer == 1 ? -.8D : 1D) + layer * TAU / 3D;
            double radius = .43D + Math.sin(clock * 1.5D + layer) * .025D;
            Vec3d center = position.add(0D, y, 0D);
            int ringColor = layer == 1 ? accent : color;
            ground(glow, FakeLagWorldGlowRenderer.Texture.PULSE, center, radius * 1.18D, phase,
                ringColor, alpha * (layer == 1 ? .33F : .20F));
            ring(center, radius, phase, TAU * .68D, alpha * .72F, ringColor, 1.4F, lines);
            ring(center, radius, phase + TAU * .77D, TAU * .075D, alpha * .85F,
                mix(ringColor, 0xFFFFFF, .68F), 1.35F, lines);
            Vec3d head = center.add(Math.cos(phase + TAU * .68D) * radius, 0D,
                Math.sin(phase + TAU * .68D) * radius);
            billboard(glow, FakeLagWorldGlowRenderer.Texture.SOFT, head, .19D, .19D,
                mix(ringColor, 0xFFFFFF, .48F), alpha * .40F);
        }
    }

    private static void trail(VisualSnapshot snapshot, Vec3d sent, Vec3d local, float alpha, int color, int accent,
                              List<WorldLineStrip> lines, GlowFrame glow, double clock) {
        List<FakeLagFeature.TrailPoint> points = snapshot.trail();
        int count = Math.min(MAX_TRAIL_POINTS, points.size());
        Vec3d previous = sent.add(0D, .055D, 0D);
        List<FakeLagConnectedRibbon.Node> nodes = new ArrayList<>(count + 2);
        nodes.add(new FakeLagConnectedRibbon.Node(previous, .20D, argb(alpha * .18F, color)));
        int remaining = 80;
        for (int index = 0; index < count; index++) {
            int source = count <= 1 ? 0 : index * (points.size() - 1) / (count - 1);
            Vec3d point = points.get(source).position().add(0D, .055D, 0D);
            float progress = (index + 1F) / Math.max(1, count);
            double distanceSquared = previous.squaredDistanceTo(point);
            float fade = alpha * (.12F + progress * .66F);
            if (FakeLagAnimation.shouldSnap(distanceSquared)) {
                remaining -= connected(glow, nodes, null, remaining, false);
                nodes.clear();
            }
            if (distanceSquared > .0001D) {
                int segmentColor = mix(color, accent, progress * .6F);
                nodes.add(new FakeLagConnectedRibbon.Node(point, .20D + progress * .10D,
                    argb(fade * .72F, segmentColor)));
            }
            previous = point;
        }
        Vec3d end = local.add(0D, .055D, 0D);
        if (FakeLagAnimation.shouldSnap(previous.squaredDistanceTo(end))) {
            connected(glow, nodes, null, remaining, false);
            return;
        }
        nodes.add(new FakeLagConnectedRibbon.Node(end, .30D, argb(alpha * .60F, accent)));
        connected(glow, nodes, null, remaining, false);
    }

    private static void tether(Vec3d sent, Vec3d local, float alpha, int color, int accent,
                               List<WorldLineStrip> lines, GlowFrame glow, double clock) {
        Vec3d start = sent.add(0D, .94D, 0D), end = local.add(0D, .94D, 0D);
        double length = start.distanceTo(end);
        if (length > 8D || length < .025D) return;
        double bend = Math.min(.28D, length * .055D);
        List<FakeLagConnectedRibbon.Node> nodes = new ArrayList<>(33);
        for (int segment = 0; segment <= 32; segment++) {
            double progress = segment / 32D;
            Vec3d point = curve(start, end, bend, progress);
            int segmentColor = mix(color, accent, (float) progress);
            nodes.add(new FakeLagConnectedRibbon.Node(point, .18D, argb(alpha * .42F, segmentColor)));
        }
        connected(glow, nodes, null, 32, false);
        double head = clock * .46D - Math.floor(clock * .46D);
        for (int segment = 0; segment < 5; segment++) {
            double a = head - .13D + segment * .026D;
            double b = a + .026D;
            if (a < 0D || b > 1D) continue;
            float fade = alpha * (segment + 1) / 5F;
            Vec3d from = curve(start, end, bend, a), to = curve(start, end, bend, b);
            streak(glow, from, to, .14D, mix(color, 0xFFFFFF, .75F), fade * .60F);
            line(List.of(from, to), mix(color, 0xFFFFFF, .72F), fade * .80F, 1.45F, lines);
        }
        Vec3d comet = curve(start, end, bend, head);
        float edgeFade = (float) Math.min(1D, Math.min(head, 1D - head) * 12D);
        billboard(glow, FakeLagWorldGlowRenderer.Texture.SOFT, comet, .16D, .16D,
            mix(color, 0xFFFFFF, .72F), alpha * .52F * edgeFade);
    }

    private static Vec3d curve(Vec3d start, Vec3d end, double bend, double progress) {
        return start.lerp(end, progress).add(0D, Math.sin(progress * Math.PI) * bend, 0D);
    }

    private static void ring(Vec3d center, double radius, double phase, double arc, float alpha,
                             int color, float width, List<WorldLineStrip> lines) {
        int segments = Math.max(4, Math.min(64, (int) Math.ceil(56D * arc / TAU)));
        List<Vec3d> points = new ArrayList<>(segments + 1);
        for (int index = 0; index <= segments; index++) {
            double angle = phase + arc * index / segments;
            points.add(center.add(Math.cos(angle) * radius, 0D, Math.sin(angle) * radius));
        }
        line(points, color, alpha, width, lines);
    }

    private static void line(List<Vec3d> points, int color, float alpha, float width,
                              List<WorldLineStrip> lines) {
        if (alpha > .003F) lines.add(new WorldLineStrip(List.copyOf(points), argb(alpha, color), width));
    }

    private static void billboard(GlowFrame frame, FakeLagWorldGlowRenderer.Texture texture, Vec3d center,
                                  double width, double height, int color, float alpha) {
        Vec3d u = frame.right().multiply(width * .5D), v = frame.up().multiply(height * .5D);
        quad(frame, texture, center.subtract(u).add(v), center.add(u).add(v),
            center.add(u).subtract(v), center.subtract(u).subtract(v), color, alpha);
    }

    private static void ground(GlowFrame frame, FakeLagWorldGlowRenderer.Texture texture, Vec3d center,
                               double radius, double rotation, int color, float alpha) {
        Vec3d u = new Vec3d(Math.cos(rotation) * radius, 0D, Math.sin(rotation) * radius);
        Vec3d v = new Vec3d(-Math.sin(rotation) * radius, 0D, Math.cos(rotation) * radius);
        quad(frame, texture, center.subtract(u).subtract(v), center.add(u).subtract(v),
            center.add(u).add(v), center.subtract(u).add(v), color, alpha);
    }

    private static void streak(GlowFrame frame, Vec3d from, Vec3d to, double width, int color, float alpha) {
        Vec3d direction = to.subtract(from);
        if (direction.lengthSquared() < 1.0E-8D) return;
        Vec3d view = frame.eye().subtract(from.add(to).multiply(.5D));
        Vec3d side = direction.crossProduct(view);
        if (side.lengthSquared() < 1.0E-8D) side = direction.crossProduct(frame.up());
        if (side.lengthSquared() < 1.0E-8D) side = direction.crossProduct(frame.right());
        if (side.lengthSquared() < 1.0E-8D) return;
        side = side.normalize().multiply(width * .5D);
        // V is the texture's long axis; U spans the soft edge across the ribbon.
        quad(frame, FakeLagWorldGlowRenderer.Texture.STREAK, from.subtract(side), from.add(side),
            to.add(side), to.subtract(side), color, alpha);
    }

    private static void quad(GlowFrame frame, FakeLagWorldGlowRenderer.Texture texture, Vec3d a, Vec3d b,
                             Vec3d c, Vec3d d, int color, float alpha) {
        if (alpha <= .001F || frame.quads().size() >= frame.quadLimit()
            || frame.quads().size() - frame.startIndex() >= MAX_GLOW_QUADS) return;
        frame.quads().add(new FakeLagWorldGlowRenderer.Quad(texture, a, b, c, d, argb(alpha * GLOW_GAIN, color)));
    }

    private static int connected(GlowFrame frame, List<FakeLagConnectedRibbon.Node> nodes, Vec3d axis,
                                  int requestedSegments, boolean closed) {
        int available = Math.min(frame.quadLimit() - frame.quads().size(),
            MAX_GLOW_QUADS - (frame.quads().size() - frame.startIndex()));
        if (available <= 0) return 0;
        return FakeLagConnectedRibbon.append(frame.quads(), nodes, frame.eye(), axis,
            Math.min(available, requestedSegments), closed);
    }

    private static int mix(int first, int second, float amount) {
        float t = Math.max(0F, Math.min(1F, amount));
        int r = Math.round((first >> 16 & 255) * (1F - t) + (second >> 16 & 255) * t);
        int g = Math.round((first >> 8 & 255) * (1F - t) + (second >> 8 & 255) * t);
        int b = Math.round((first & 255) * (1F - t) + (second & 255) * t);
        return r << 16 | g << 8 | b;
    }

    private static int argb(float alpha, int color) {
        return dev.c4lam.celeste.render.geometry.WorldBox.argb(alpha, color);
    }

    private static void clearAnimation() {
        PLAYER_ECHOES.clear();
        shownWorld = null;
        visibility = 0F;
        lastFrameNanos = 0L;
        shownPosition = null;
        previousObservedPosition = null;
        shownYaw = 0D;
    }

    private record GlowFrame(List<FakeLagWorldGlowRenderer.Quad> quads, int startIndex, int quadLimit,
                             Vec3d eye, Vec3d right, Vec3d up) { }
}
