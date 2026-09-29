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
    private final AmbientTesseractField tesseracts = new AmbientTesseractField(System.nanoTime());
    private final AmbientTronField tronTrails = new AmbientTronField(System.nanoTime());
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
        AmbientField.Style frameStyle = style;
        boolean cubes = frameStyle == AmbientField.Style.TESSERACTS;
        int cx = MathHelper.floor(eye.x / AmbientField.CELL), cy = MathHelper.floor(eye.y / AmbientField.CELL);
        int cz = MathHelper.floor(eye.z / AmbientField.CELL);
        double frameDensity = density, frameSize = size;
        double skySeconds = skyMotion.sample(System.nanoTime(), client.isPaused() || !skyLines, skySpeed);
        SkyLinesRenderer.Frame sky = skyLines && skyIntensity > 0
            ? new SkyLinesRenderer.Frame(atmosphere(SkyLinesMath.sample(skyPattern, skySeconds, skyIntensity), seconds))
            : SkyLinesRenderer.Frame.EMPTY;
        List<AmbientEffectsRenderer.Particle> particles = new ArrayList<>(96);
        List<AmbientEffectsRenderer.TronSegment> trails = new ArrayList<>();
        BlockPos.Mutable block = new BlockPos.Mutable();
        if (frameStyle == AmbientField.Style.TRON_TRAILS) {
            AmbientTronField.AirCell air = (x, y, z) -> {
                block.set(x, y, z);
                return !context.world().isOutOfHeightLimit(block) && context.world().isChunkLoaded(block)
                    && context.world().getBlockState(block).isAir() && context.world().getFluidState(block).isEmpty();
            };
            for (AmbientTronField.Segment segment : tronTrails.sample(eye.x, eye.y, eye.z, seconds, frameDensity, frameSize, air)) {
                double bound = segment.width() * 1.1;
                if (context.frustum() != null && !context.frustum().isVisible(new Box(
                    Math.min(segment.ax(), segment.bx()) - bound, segment.ay() - bound,
                    Math.min(segment.az(), segment.bz()) - bound, Math.max(segment.ax(), segment.bx()) + bound,
                    segment.by() + bound, Math.max(segment.az(), segment.bz()) + bound))) continue;
                trails.add(new AmbientEffectsRenderer.TronSegment(segment,
                    VisualAtmosphere.tint(0x57DDEB, seconds, segment.palette())));
            }
        } else if (cubes) {
            AmbientTesseractField.AirCell air = (x, y, z) -> {
                block.set(x, y, z);
                return !context.world().isOutOfHeightLimit(block) && context.world().isChunkLoaded(block)
                    && context.world().getBlockState(block).isAir() && context.world().getFluidState(block).isEmpty();
            };
            List<AmbientTesseractField.Candidate> candidates = tesseracts.candidates(eye.x, eye.y, eye.z, seconds, frameDensity);
            for (int rank = 0; rank < candidates.size(); rank++) {
                AmbientTesseractField.Candidate candidate = candidates.get(rank);
                AmbientField.Mote mote = candidate.mote();
                double alpha = mote.opacity() * AmbientTesseractField.capFade(rank)
                    * AmbientTesseractField.visibility(mote.x() - eye.x, mote.y() - eye.y, mote.z() - eye.z);
                if (alpha < .01) continue;
                double radius = mote.size() * frameSize, bound = AmbientTesseractField.bounds(radius);
                // Slots are already reserved: turning the camera cannot replace unrelated particles.
                if (context.frustum() != null && !context.frustum().isVisible(new Box(mote.x() - bound,
                    mote.y() - bound, mote.z() - bound, mote.x() + bound, mote.y() + bound, mote.z() + bound))) continue;
                alpha *= AmbientTesseractField.airVisibility(mote.x(), mote.y(), mote.z(), radius, air);
                if (alpha < .01) continue;
                int color = VisualAtmosphere.tint(mote.color(), seconds, (candidate.priority() >>> 11) * 0x1.0p-53);
                particles.add(new AmbientEffectsRenderer.Particle(new Vec3d(mote.x(), mote.y(), mote.z()),
                    radius, mote.yaw(), mote.pitch(), (float)alpha, color));
            }
        } else if (frameDensity > 0) for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) {
            for (int y = cy - 3; y <= cy + 3; y++) {
                if (!AmbientField.occupied(x, y, z, frameDensity)
                    || !AmbientField.mayBeVisible(x, y, z, eye.x, eye.y, eye.z)) continue;
                AmbientField.Mote mote = AmbientField.sample(x, y, z, frameStyle, seconds);
                double alpha = mote.opacity() * AmbientField.visibility(mote.x() - eye.x, mote.y() - eye.y, mote.z() - eye.z);
                if (alpha < .01) continue;
                double radius = mote.size() * frameSize;
                // Frustum work is independent of block state; reject it before live world queries.
                double bound = radius * 6;
                if (context.frustum() != null && !context.frustum().isVisible(new Box(mote.x() - bound,
                    mote.y() - bound, mote.z() - bound, mote.x() + bound,
                    mote.y() + bound, mote.z() + bound))) continue;
                block.set(mote.x(), mote.y(), mote.z());
                if (context.world().isOutOfHeightLimit(block) || !context.world().isChunkLoaded(block)
                    || !context.world().getBlockState(block).isAir()
                    || !context.world().getFluidState(block).isEmpty()
                    || !context.world().isSkyVisible(block)) continue;
                int color = frameStyle == AmbientField.Style.LEAVES ? mote.color()
                    : VisualAtmosphere.tint(mote.color(), seconds,
                        (AmbientTesseractField.priority(x, y, z) >>> 11) * 0x1.0p-53);
                AmbientEffectsRenderer.Particle particle = new AmbientEffectsRenderer.Particle(new Vec3d(mote.x(), mote.y(), mote.z()),
                    radius, mote.yaw(), mote.pitch(), (float) alpha, color);
                particles.add(particle);
            }
        }
        particles.sort(Comparator.comparingDouble(p -> p.position().squaredDistanceTo(eye)));
        int cap = cubes ? AmbientTesseractField.MAX_PARTICLES : 128;
        if (particles.size() > cap) particles.subList(cap, particles.size()).clear();
        java.util.Collections.reverse(particles); // Back-to-front translucent core batches.
        Vector3f right = context.camera().getRotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = context.camera().getRotation().transform(new Vector3f(0, 1, 0));
        return particles.isEmpty() && sky.isEmpty() && trails.isEmpty() ? AmbientEffectsRenderer.Frame.EMPTY : new AmbientEffectsRenderer.Frame(
            List.copyOf(particles), frameStyle, new Vec3d(right.x, right.y, right.z), new Vec3d(up.x, up.y, up.z), sky,
            List.copyOf(trails));
    }

    private static SkyLinesMath.Geometry atmosphere(SkyLinesMath.Geometry geometry, double seconds) {
        if (!VisualAtmosphere.usesPreset() || geometry.isEmpty()) return geometry;
        List<SkyLinesMath.Segment> segments = new ArrayList<>(geometry.segments().size());
        List<SkyLinesMath.Star> stars = new ArrayList<>(geometry.stars().size());
        for (int i = 0; i < geometry.segments().size(); i++) {
            SkyLinesMath.Segment segment = geometry.segments().get(i);
            segments.add(new SkyLinesMath.Segment(segment.from(), segment.to(), segment.opacity(), segment.width(),
                VisualAtmosphere.tint(segment.color(), seconds, (double)i / geometry.segments().size())));
        }
        for (int i = 0; i < geometry.stars().size(); i++) {
            SkyLinesMath.Star star = geometry.stars().get(i);
            stars.add(new SkyLinesMath.Star(star.position(), star.opacity(), star.radius(),
                VisualAtmosphere.tint(star.color(), seconds, (double)i / geometry.stars().size())));
        }
        return new SkyLinesMath.Geometry(List.copyOf(segments), List.copyOf(stars));
    }

    private void update(ModuleContext context) {
        skyLines = context.toggle("Sky Lines", true);
        skyPattern = switch (context.mode("Sky Pattern", "Orbital Ribbons")) {
            case "Constellation Arcs" -> SkyLinesMath.Pattern.CONSTELLATION_ARCS;
            case "Light Rails" -> SkyLinesMath.Pattern.LIGHT_RAILS;
            default -> SkyLinesMath.Pattern.ORBITAL_RIBBONS;
        };
        skyIntensity = finiteRange(context.slider("Sky Intensity", .65), 0, 1, .65);
        skySpeed = finiteRange(context.slider("Sky Speed", .6), 0, 2, .6);
        style = switch (context.mode("Style", "Fireflies")) {
            case "Crystal Motes" -> AmbientField.Style.CRYSTALS;
            case "Snowfall" -> AmbientField.Style.SNOW;
            case "Falling Leaves" -> AmbientField.Style.LEAVES;
            case "Tesseracts" -> AmbientField.Style.TESSERACTS;
            case "Tron Trails" -> AmbientField.Style.TRON_TRAILS;
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
    private void reset() { observedWorld = null; clock.reset(); skyMotion.reset(); tesseracts.reset(); tronTrails.reset(); }
}
