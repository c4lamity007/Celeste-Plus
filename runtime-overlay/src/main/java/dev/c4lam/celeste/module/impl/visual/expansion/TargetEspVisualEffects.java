package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.render.geometry.WorldLineStrip;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Small, bounded geometry builders shared by the world-space visual effects. */
final class TargetEspVisualEffects {
    static final double TAU = Math.PI * 2.0D;
    static final Vec3d X = new Vec3d(1.0D, 0.0D, 0.0D);
    static final Vec3d Z = new Vec3d(0.0D, 0.0D, 1.0D);

    private TargetEspVisualEffects() {
    }

    static int color(String theme, int custom, double time, double offset) {
        double blend = 0.5D + 0.5D * Math.sin(time * 0.65D + offset * TAU);
        int local = switch (theme) {
            case "Ice" -> mix(0x79DCFF, 0xD3B9FF, blend);
            case "Sunset" -> mix(0xFF9B73, 0xFF639F, blend);
            case "Mono" -> mix(0xB8C3D3, 0xFFFFFF, blend);
            case "Custom" -> mix(custom, 0xFFFFFF, blend * 0.18D);
            default -> mix(0xFF4FA3, 0xA987FF, blend);
        };
        return TargetEspVisualAtmosphere.tint(local, time, offset);
    }

    static int mix(int first, int second, double blend) {
        double t = MathHelper.clamp(blend, 0.0D, 1.0D);
        int red = (int) Math.round((first >> 16 & 255) * (1.0D - t) + (second >> 16 & 255) * t);
        int green = (int) Math.round((first >> 8 & 255) * (1.0D - t) + (second >> 8 & 255) * t);
        int blue = (int) Math.round((first & 255) * (1.0D - t) + (second & 255) * t);
        return red << 16 | green << 8 | blue;
    }

    static List<Vec3d> arc(Vec3d center, Vec3d axisU, Vec3d axisV, double radius,
                           double start, double sweep, int segments) {
        int count = MathHelper.clamp(segments, 2, 96);
        List<Vec3d> points = new ArrayList<>(count + 1);
        for (int i = 0; i <= count; i++) {
            double angle = start + sweep * i / count;
            points.add(center.add(axisU.multiply(Math.cos(angle) * radius))
                .add(axisV.multiply(Math.sin(angle) * radius)));
        }
        return List.copyOf(points);
    }

    static void line(List<WorldLineStrip> output, List<Vec3d> points,
                     int color, double opacity, float width, double glow) {
        if (points.size() < 2 || opacity <= 0.002D) return;
        float alpha = (float) MathHelper.clamp(opacity, 0.0D, 1.0D);
        List<Vec3d> frozen = List.copyOf(points);
        if (glow > 0.01D) {
            output.add(new WorldLineStrip(frozen,
                dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (alpha * glow * 0.10D), color), width + 5.5F));
            output.add(new WorldLineStrip(frozen,
                dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (alpha * glow * 0.22D), color), width + 2.4F));
        }
        output.add(new WorldLineStrip(frozen,
            dev.c4lam.celeste.render.geometry.WorldBox.argb(alpha, color), width));
    }

    /** Color/alpha vary across short strips so tails fade without renderer state changes. */
    static void trail(List<WorldLineStrip> output, List<Vec3d> points,
                      String theme, int custom, double time, double opacity,
                      double startAlpha, double endAlpha, float width, double glow, int bands) {
        if (points.size() < 2) return;
        int segments = points.size() - 1;
        int count = Math.min(MathHelper.clamp(bands, 1, 16), segments);
        for (int band = 0; band < count; band++) {
            int from = band * segments / count;
            int to = (band + 1) * segments / count;
            double progress = (band + 0.5D) / count;
            double alpha = opacity * (startAlpha + (endAlpha - startAlpha) * progress);
            line(output, points.subList(from, to + 1), color(theme, custom, time, progress), alpha, width, glow);
        }
    }
}
