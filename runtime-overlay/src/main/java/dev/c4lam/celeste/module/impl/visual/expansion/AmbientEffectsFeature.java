package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.gui.model.AmbientEffectsSettings;
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
    private final AmbientEffectsLayers.State state = new AmbientEffectsLayers.State(System.nanoTime());
    private final SkyLinesMath.Motion skyMotion = new SkyLinesMath.Motion();
    private volatile boolean enabled;
    private volatile boolean skyLines = true;
    private volatile SkyLinesMath.Pattern skyPattern = SkyLinesMath.Pattern.ORBITAL_RIBBONS;
    private volatile double skyIntensity = .65, skySpeed = .6;
    private volatile List<AmbientEffectsLayers.Layer> layers = List.of(
        new AmbientEffectsLayers.Layer(AmbientField.Style.FIREFLIES,.6,1));

    @Override public String name() { return "Ambient Effects"; }
    @Override public void onEnable(ModuleContext context) { reset(); update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }
    @Override public void onStartTick(ModuleContext context) { update(context); }

    public AmbientEffectsRenderer.Frame extract(WorldExtractionContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || context.world() == null || context.camera() == null || client.player == null
            || !context.camera().isReady() || !client.player.isAlive() || client.player.getEntityWorld() != context.world()
            || !context.world().getRegistryKey().equals(World.OVERWORLD)
            || context.camera().getSubmersionType() != CameraSubmersionType.NONE) return hidden();
        if (context.camera().getFocusedEntity() instanceof LivingEntity focused
            && (focused.hasStatusEffect(StatusEffects.BLINDNESS)
                || focused.hasStatusEffect(StatusEffects.DARKNESS))) return hidden();
        List<AmbientEffectsLayers.Layer> frameLayers = layers;
        if (!state.isWorld(context.world())) skyMotion.reset();
        double seconds = state.sample(context.world(),System.nanoTime(),client.isPaused(),frameLayers);
        Vec3d eye = context.camera().getCameraPos();
        if (!Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z)) return hidden();

        int cx = MathHelper.floor(eye.x / AmbientField.CELL), cy = MathHelper.floor(eye.y / AmbientField.CELL);
        int cz = MathHelper.floor(eye.z / AmbientField.CELL);
        double skySeconds = skyMotion.sample(System.nanoTime(), client.isPaused() || !skyLines, skySpeed);
        SkyLinesRenderer.Frame sky = skyLines && skyIntensity > 0
            ? new SkyLinesRenderer.Frame(atmosphere(SkyLinesMath.sample(skyPattern, skySeconds, skyIntensity), seconds))
            : SkyLinesRenderer.Frame.EMPTY;
        List<AmbientEffectsRenderer.Particle> allParticles = new ArrayList<>(AmbientEffectsLayers.MAX_PARTICLES);
        List<AmbientEffectsRenderer.TronSegment> trails = new ArrayList<>();
        List<AmbientEffectsRenderer.LightStrand> strands = new ArrayList<>();
        BlockPos.Mutable block = new BlockPos.Mutable();
        AmbientEffectsAirCache airCache = new AmbientEffectsAirCache(new AmbientEffectsAirCache.Cells() {
            @Override public boolean clear(int x,int y,int z) {
                block.set(x,y,z);
                return !context.world().isOutOfHeightLimit(block) && context.world().isChunkLoaded(block)
                    && context.world().getBlockState(block).isAir() && context.world().getFluidState(block).isEmpty();
            }
            @Override public boolean skyVisible(int x,int y,int z) {
                block.set(x,y,z);
                return context.world().isSkyVisible(block);
            }
        });
        int activeCount = (int)frameLayers.stream().filter(layer -> layer.density()>0).count();
        for (AmbientEffectsLayers.Layer layer : frameLayers) {
            AmbientField.Style frameStyle = layer.style();
            double frameDensity = layer.density(), frameSize = layer.size();
            if (frameDensity <= 0) continue;
            airCache.beginLayer(activeCount);
            boolean cubes = frameStyle == AmbientField.Style.TESSERACTS;
            int cap = AmbientEffectsLayers.particleCap(frameLayers,frameStyle);
            List<AmbientEffectsRenderer.Particle> particles = new ArrayList<>(cap);
            if (frameStyle == AmbientField.Style.LIGHT_STRANDS) {
                for (AmbientLightStrands.Strand strand : AmbientLightStrands.sample(eye.x, eye.y, eye.z,
                    seconds, frameDensity, airCache::clear, (x,y,z,bound) -> context.frustum() == null
                        || context.frustum().isVisible(new Box(x-bound,y-bound,z-bound,x+bound,y+bound,z+bound)))) {
                    List<Vec3d> points = new ArrayList<>(strand.points().size());
                    for (AmbientLightStrands.Point p : strand.points()) points.add(new Vec3d(p.x(),p.y(),p.z()));
                    strands.add(new AmbientEffectsRenderer.LightStrand(List.copyOf(points), frameSize,
                        (float)strand.opacity(), AmbientEffectsLayers.color(layer,seconds,strand.palette())));
                }
            } else if (frameStyle == AmbientField.Style.TRON_TRAILS) {
                AmbientTronField.AirCell air = airCache::clear;
                for (AmbientTronField.Segment segment : state.trails().sample(eye.x, eye.y, eye.z, seconds, frameDensity, frameSize, air)) {
                    double bound = segment.width() * 1.5;
                    if (context.frustum() != null && !context.frustum().isVisible(new Box(
                        Math.min(segment.ax(), segment.bx()) - bound, Math.min(segment.ay(),segment.by()) - bound,
                        Math.min(segment.az(), segment.bz()) - bound, Math.max(segment.ax(), segment.bx()) + bound,
                        Math.max(segment.ay(),segment.by()) + bound, Math.max(segment.az(), segment.bz()) + bound))) continue;
                    trails.add(new AmbientEffectsRenderer.TronSegment(segment,
                        AmbientEffectsLayers.color(layer,seconds,segment.palette())));
                }
            } else if (cubes) {
                AmbientTesseractField.AirCell air = airCache::clear;
                List<AmbientTesseractField.Candidate> candidates = state.tesseracts().candidates(eye.x, eye.y, eye.z, seconds, frameDensity);
                for (int rank = 0; rank < Math.min(cap,candidates.size()); rank++) {
                    AmbientTesseractField.Candidate candidate = candidates.get(rank);
                    AmbientField.Mote mote = candidate.mote();
                    double alpha = mote.opacity() * (cap == AmbientTesseractField.MAX_PARTICLES ? AmbientTesseractField.capFade(rank)
                        : AmbientField.smooth((cap - 1.0 - rank) / Math.min(32 * AmbientField.DENSITY_COPIES,cap * .25)))
                        * AmbientTesseractField.visibility(mote.x() - eye.x, mote.y() - eye.y, mote.z() - eye.z);
                    if (alpha < .01) continue;
                    double radius = mote.size() * frameSize, bound = AmbientTesseractField.bounds(radius);
                    // Slots are already reserved: turning the camera cannot replace unrelated particles.
                    if (context.frustum() != null && !context.frustum().isVisible(new Box(mote.x() - bound,
                        mote.y() - bound, mote.z() - bound, mote.x() + bound, mote.y() + bound, mote.z() + bound))) continue;
                    alpha *= AmbientTesseractField.airVisibility(mote.x(), mote.y(), mote.z(), radius, air);
                    if (alpha < .01) continue;
                    int color = AmbientEffectsLayers.color(layer,seconds,(candidate.priority() >>> 11) * 0x1.0p-53);
                    particles.add(new AmbientEffectsRenderer.Particle(new Vec3d(mote.x(), mote.y(), mote.z()),
                        radius, mote.yaw(), mote.pitch(), (float)alpha, color, frameStyle));
                }
            } else if (frameDensity > 0) for (int x = cx - 5; x <= cx + 5; x++) for (int z = cz - 5; z <= cz + 5; z++) {
                for (int y = cy - 3; y <= cy + 3; y++) {
                    if (!AmbientField.occupied(x, y, z, frameDensity)
                        || !AmbientField.mayBeVisible(x, y, z, eye.x, eye.y, eye.z)) continue;
                    for (int lane=0;lane<AmbientField.DENSITY_COPIES;lane++) {
                        AmbientField.Mote mote = AmbientField.sample(x,y,z,frameStyle,seconds,lane);
                        double alpha = mote.opacity() * AmbientField.visibility(mote.x() - eye.x, mote.y() - eye.y, mote.z() - eye.z);
                        if (alpha < .01) continue;
                        double radius = mote.size() * frameSize;
                        // Frustum work is independent of block state; reject it before live world queries.
                        double bound = radius * 6;
                        if (context.frustum() != null && !context.frustum().isVisible(new Box(mote.x() - bound,
                            mote.y() - bound, mote.z() - bound, mote.x() + bound,
                            mote.y() + bound, mote.z() + bound))) continue;
                        if (!airCache.openSky(MathHelper.floor(mote.x()),MathHelper.floor(mote.y()),MathHelper.floor(mote.z()))) continue;
                        int color = AmbientEffectsLayers.color(layer,seconds,
                            (AmbientTesseractField.priority(x,y,z) >>> 11) * 0x1.0p-53 + lane/(double)AmbientField.DENSITY_COPIES);
                        AmbientEffectsRenderer.Particle particle = new AmbientEffectsRenderer.Particle(new Vec3d(mote.x(), mote.y(), mote.z()),
                            radius, mote.yaw(), mote.pitch(), (float) alpha, color, frameStyle);
                        particles.add(particle);
                    }
                }
            }
            if (particles.size() > cap) {
                particles.sort(Comparator.comparingDouble(p -> p.position().squaredDistanceTo(eye)));
                particles.subList(cap, particles.size()).clear();
            }
            allParticles.addAll(particles);
        }
        // Interleaved styles share one back-to-front translucent batch.
        allParticles.sort(Comparator.comparingDouble((AmbientEffectsRenderer.Particle p) -> p.position().squaredDistanceTo(eye)).reversed());
        Vector3f right = context.camera().getRotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = context.camera().getRotation().transform(new Vector3f(0, 1, 0));
        return allParticles.isEmpty() && sky.isEmpty() && trails.isEmpty() && strands.isEmpty() ? AmbientEffectsRenderer.Frame.EMPTY : new AmbientEffectsRenderer.Frame(
            List.copyOf(allParticles), AmbientField.Style.FIREFLIES, new Vec3d(right.x, right.y, right.z), new Vec3d(up.x, up.y, up.z), sky,
            List.copyOf(trails), List.copyOf(strands));
    }

    private static SkyLinesMath.Geometry atmosphere(SkyLinesMath.Geometry geometry, double seconds) {
        if (!AmbientEffectsVisualAtmosphere.usesPreset() || geometry.isEmpty()) return geometry;
        List<SkyLinesMath.Segment> segments = new ArrayList<>(geometry.segments().size());
        List<SkyLinesMath.Star> stars = new ArrayList<>(geometry.stars().size());
        for (int i = 0; i < geometry.segments().size(); i++) {
            SkyLinesMath.Segment segment = geometry.segments().get(i);
            segments.add(new SkyLinesMath.Segment(segment.from(), segment.to(), segment.opacity(), segment.width(),
                AmbientEffectsVisualAtmosphere.tint(segment.color(), seconds, (double)i / geometry.segments().size())));
        }
        for (int i = 0; i < geometry.stars().size(); i++) {
            SkyLinesMath.Star star = geometry.stars().get(i);
            stars.add(new SkyLinesMath.Star(star.position(), star.opacity(), star.radius(),
                AmbientEffectsVisualAtmosphere.tint(star.color(), seconds, (double)i / geometry.stars().size())));
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
        List<AmbientEffectsLayers.Layer> snapshot = new ArrayList<>(4);
        for (AmbientEffectsSettings.Layer layer : AmbientEffectsSettings.layers(context.module())) {
            AmbientField.Style style = switch (layer.style()) {
                case "Light Strands" -> AmbientField.Style.LIGHT_STRANDS;
                case "Tesseracts" -> AmbientField.Style.TESSERACTS;
                case "Tron Trails" -> AmbientField.Style.TRON_TRAILS;
                default -> AmbientField.Style.FIREFLIES;
            };
            snapshot.add(new AmbientEffectsLayers.Layer(style,layer.density(),layer.size(),layer.color()));
        }
        layers = AmbientEffectsLayers.normalize(snapshot);
    }

    private static double finiteRange(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? MathHelper.clamp(value, min, max) : fallback;
    }
    private AmbientEffectsRenderer.Frame hidden() {
        if (state.hasWorld()) reset();
        return AmbientEffectsRenderer.Frame.EMPTY;
    }
    private void reset() { state.reset(); skyMotion.reset(); }
}
