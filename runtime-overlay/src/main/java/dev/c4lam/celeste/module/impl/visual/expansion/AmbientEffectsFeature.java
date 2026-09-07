package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A bounded field of real, depth-tested 3D motes; never creates game particles or entities. */
public final class AmbientEffectsFeature implements ModuleFeature {
    private final JumpCircleAnimation.Clock clock = new JumpCircleAnimation.Clock();
    private final SkyLinesMath.Motion skyMotion = new SkyLinesMath.Motion();
    private volatile boolean enabled;
    private volatile boolean skyLines = true;
    private volatile SkyLinesMath.Pattern skyPattern = SkyLinesMath.Pattern.ORBITAL_RIBBONS;
    private volatile double skyIntensity = .65, skySpeed = .6;
    private volatile AmbientField.Style style = AmbientField.Style.FIREFLIES;
    private volatile double density = .6;
    private volatile double size = 1.0;
    private Object observedWorld;

    @Override public String name() { return "Ambient Effects"; }
    @Override public void onEnable(ModuleContext context) { reset(); update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }
    @Override public void onStartTick(ModuleContext context) { update(context); }

    AmbientEffectsRenderer.Frame extract(WorldExtractionContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || context.world() == null || context.camera() == null || client.player == null
            || !context.camera().isReady() || !client.player.isAlive() || client.player.getEntityWorld() != context.world()
            || !context.world().getRegistryKey().equals(World.OVERWORLD)
            || context.camera().getSubmersionType() != CameraSubmersionType.NONE) return AmbientEffectsRenderer.Frame.EMPTY;
        if (context.camera().getFocusedEntity() instanceof LivingEntity focused
            && (focused.hasStatusEffect(StatusEffects.BLINDNESS)
                || focused.hasStatusEffect(StatusEffects.DARKNESS))) return AmbientEffectsRenderer.Frame.EMPTY;
        if (observedWorld != context.world()) { reset(); observedWorld = context.world(); }
        double seconds = clock.sample(System.nanoTime(), client.isPaused());
        Vec3d eye = context.camera().getCameraPos();
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) return AmbientEffectsRenderer.Frame.EMPTY;
        int cx = MathHelper.floor(eye.x / AmbientField.CELL), cy = MathHelper.floor(eye.y / AmbientField.CELL);
        int cz = MathHelper.floor(eye.z / AmbientField.CELL);
        AmbientField.Style frameStyle = style;
        double frameDensity = density, frameSize = size;
        double skySeconds = skyMotion.sample(System.nanoTime(), client.isPaused() || !skyLines, skySpeed);
        SkyLinesRenderer.Frame sky = skyLines && skyIntensity > 0
            ? new SkyLinesRenderer.Frame(SkyLinesMath.sample(skyPattern, skySeconds, skyIntensity))
            : SkyLinesRenderer.Frame.EMPTY;
        List<AmbientEffectsRenderer.Particle> particles = new ArrayList<>(96);
        BlockPos.Mutable block = new BlockPos.Mutable();
        if (frameDensity > 0) for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) {
            for (int y = cy - 3; y <= cy + 3; y++) {
                if (!AmbientField.occupied(x, y, z, frameDensity)
                    || !AmbientField.mayBeVisible(x, y, z, eye.x, eye.y, eye.z)) continue;
                AmbientField.Mote mote = AmbientField.sample(x, y, z, frameStyle, seconds);
                double alpha = mote.opacity() * AmbientField.visibility(mote.x() - eye.x, mote.y() - eye.y, mote.z() - eye.z);
                if (alpha < .01) continue;
                double radius = mote.size() * frameSize;
                // Frustum work is independent of block state; reject it before live world queries.
                if (context.frustum() != null && !context.frustum().isVisible(new Box(mote.x() - radius * 6,
                    mote.y() - radius * 6, mote.z() - radius * 6, mote.x() + radius * 6,
                    mote.y() + radius * 6, mote.z() + radius * 6))) continue;
                block.set(mote.x(), mote.y(), mote.z());
                if (context.world().isOutOfHeightLimit(block) || !context.world().isChunkLoaded(block)
                    || !context.world().getBlockState(block).isAir()
                    || !context.world().getFluidState(block).isEmpty()
                    || !context.world().isSkyVisible(block)) continue;
                particles.add(new AmbientEffectsRenderer.Particle(new Vec3d(mote.x(), mote.y(), mote.z()),
                    radius, mote.yaw(), mote.pitch(), (float) alpha, mote.color()));
            }
        }
        // Stable nearest-first cap only guards pathological inputs; normal maximum density stays below it.
        particles.sort(Comparator.comparingDouble(p -> p.position().squaredDistanceTo(eye)));
        if (particles.size() > 128) particles.subList(128, particles.size()).clear();
        java.util.Collections.reverse(particles); // Back-to-front translucent core batches.
        Vector3f right = context.camera().getRotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = context.camera().getRotation().transform(new Vector3f(0, 1, 0));
        return particles.isEmpty() && sky.isEmpty() ? AmbientEffectsRenderer.Frame.EMPTY : new AmbientEffectsRenderer.Frame(
            List.copyOf(particles), frameStyle, new Vec3d(right.x, right.y, right.z), new Vec3d(up.x, up.y, up.z), sky);
    }

    private void update(ModuleContext context) {
        skyLines = context.toggle("Sky Lines", true);
        skyPattern = "Constellation Arcs".equals(context.mode("Sky Pattern", "Orbital Ribbons"))
            ? SkyLinesMath.Pattern.CONSTELLATION_ARCS : SkyLinesMath.Pattern.ORBITAL_RIBBONS;
        skyIntensity = finiteRange(context.slider("Sky Intensity", .65), 0, 1, .65);
        skySpeed = finiteRange(context.slider("Sky Speed", .6), 0, 2, .6);
        style = switch (context.mode("Style", "Fireflies")) {
            case "Crystal Motes" -> AmbientField.Style.CRYSTALS;
            case "Snowfall" -> AmbientField.Style.SNOW;
            default -> AmbientField.Style.FIREFLIES;
        };
        double value = context.slider("Density", .6);
        density = Double.isFinite(value) ? MathHelper.clamp(value, 0, 1) : .6;
        value = context.slider("Size", 1);
        size = Double.isFinite(value) ? MathHelper.clamp(value, .5, 2) : 1;
    }

    private static double finiteRange(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? MathHelper.clamp(value, min, max) : fallback;
    }
    private void reset() { observedWorld = null; clock.reset(); skyMotion.reset(); }
}
