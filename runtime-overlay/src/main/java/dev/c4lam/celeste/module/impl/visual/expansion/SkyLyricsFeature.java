package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.media.TimedLyrics;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.compat.RenderCompatibility;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;

/** A single, fixed world-space lyric cue synchronized to the existing media session. */
public final class SkyLyricsFeature implements ModuleFeature {
    private volatile boolean enabled;
    private volatile MediaPlaybackSnapshot media = MediaPlaybackSnapshot.empty("");
    private record Settings(float size, float opacity, int color, int endColor, int gradient, double distance) { }
    private record Track(String source, String title, String artist) { }
    private volatile Settings settings = new Settings(1, .9f, 0xF4F0FF, 0x9CE5F2, 0, 18);
    private final SkyLyricAnchor anchor = new SkyLyricAnchor();
    private final SkyLyricClock clock = new SkyLyricClock();
    private Track track;
    private TimedLyrics cachedLyrics;
    private TimedLyrics.WordCue cachedCue = TimedLyrics.WordCue.EMPTY;
    private long cachedDuration;
    public SkyLyricsFeature() {
        var resources = net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES);
        dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.register(resources, "celeste:sky_lyrics/reload",
            net.minecraft.resource.SynchronousResourceReloader.class,
            manager -> MinecraftClient.getInstance().execute(SkyLyricsRenderer::release),
            proxy -> resources.registerReloader(net.minecraft.util.Identifier.of("celeste", "sky_lyrics"), proxy));
        dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions.register(
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING, "celeste:sky_lyrics/stopping",
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStopping.class,
            client -> { stop(); SkyLyricsRenderer.release(); },
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING::register);
    }
    private void stop() {
        enabled = false;
        SystemMediaService.getInstance().setSkyLyricsEnabled(false);
        media = MediaPlaybackSnapshot.empty("");
        resetAnchor();
    }
    @Override public String name() { return "Sky Lyrics"; }
    @Override public void onEnable(ModuleContext context) {
        resetAnchor();
        enabled = true;
        SystemMediaService.getInstance().setSkyLyricsEnabled(true);
        onStartTick(context);
    }
    @Override public void onDisable(ModuleContext context) {
        stop(); SkyLyricsRenderer.release();
    }
    @Override public void onWorldChanged(ModuleContext context) {
        media = MediaPlaybackSnapshot.empty("");
        resetAnchor();
    }
    @Override public void onStartTick(ModuleContext context) {
        if (!enabled) return;
        int gradient = switch (context.mode("Gradient", "Solid")) {
            case "Horizontal" -> 1;
            case "Vertical" -> 2;
            default -> 0;
        };
        settings = new Settings((float)finite(context.slider("Size", 1), 1, .5, 2),
            (float)finite(context.slider("Opacity", .9), .9, .1, 1),
            context.colorPick("Color", 0xF4F0FF), context.colorPick("End Color", 0x9CE5F2), gradient,
            finite(context.slider("Distance", 18), 18, 2, 64));
        media = SystemMediaService.getInstance().snapshot();
    }
    SkyLyricsRenderer.Frame extract(WorldExtractionContext context) {
        MediaPlaybackSnapshot frameMedia = media;
        // A shadow extraction must not replace the real camera's anchor.
        if (RenderCompatibility.isShadowPass()) return SkyLyricsRenderer.Frame.EMPTY;
        if (!enabled || context.world() == null) {
            resetAnchor();
            return SkyLyricsRenderer.Frame.EMPTY;
        }
        // A transient media lookup failure must not move the same phrase when it returns.
        if (!frameMedia.available()) return SkyLyricsRenderer.Frame.EMPTY;
        Track nextTrack = new Track(frameMedia.sourceId(), frameMedia.title(), frameMedia.artist());
        if (!nextTrack.equals(track)) track = nextTrack;
        // Keep transport state current even while presentation is hidden or lyrics are loading.
        long playbackMs = clock.sample(track, frameMedia, System.nanoTime(), System.currentTimeMillis());
        if (frameMedia.lyrics().lines().isEmpty()) return SkyLyricsRenderer.Frame.EMPTY;
        // This is depth-tested world text, not a HUD widget: F1 does not hide it.
        if (context.camera() == null || !context.camera().isReady()
            || context.camera().getSubmersionType() != CameraSubmersionType.NONE) return SkyLyricsRenderer.Frame.EMPTY;
        if (context.camera().getFocusedEntity() instanceof LivingEntity focused
            && (focused.hasStatusEffect(StatusEffects.BLINDNESS) || focused.hasStatusEffect(StatusEffects.DARKNESS)))
            return SkyLyricsRenderer.Frame.EMPTY;
        var cue = currentCue(frameMedia.lyrics(), playbackMs, frameMedia.durationMs());
        if (cue.text().isBlank()) return SkyLyricsRenderer.Frame.EMPTY;
        Settings config = settings;
        Vec3d eye = context.camera().getCameraPos();
        var rotation = context.camera().getRotation();
        var pose = anchor.sample(context.world(), track, cue.startMs(), playbackMs,
            eye.x, eye.y, eye.z, rotation.x, rotation.y, rotation.z, rotation.w, config.distance(),
            (x, y, z, dx, dy, dz, limit) -> {
                Vec3d start = new Vec3d(x, y, z);
                Vec3d end = start.add(dx * limit, dy * limit, dz * limit);
                // Outline shapes include visible non-solid blocks, not just blocks
                // with collision. Raycast only when a new phrase acquires its pose.
                var ray = new RaycastContext(start, end,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE,
                    context.camera().getFocusedEntity());
                // Vanilla's inside-shape probe advances along the ray. Near an
                // outgoing face that probe can already be outside the block.
                // Check the actual eye too, so a clipped camera cannot see through.
                BlockPos origin = BlockPos.ofFloored(start);
                var shape = ray.getBlockShape(context.world().getBlockState(origin), context.world(), origin);
                for (var box : shape.getBoundingBoxes()) {
                    if (box.contains(x - origin.getX(), y - origin.getY(), z - origin.getZ())) return 0;
                }
                var hit = context.world().raycast(ray);
                return hit.getType() == HitResult.Type.MISS
                    ? Double.POSITIVE_INFINITY : start.distanceTo(hit.getPos());
            });
        if (pose == null) return SkyLyricsRenderer.Frame.EMPTY;
        return new SkyLyricsRenderer.Frame(cue, playbackMs, config.opacity(), config.size(),
            new Vec3d(pose.x(), pose.y(), pose.z()), new Quaternionf(pose.qx(), pose.qy(), pose.qz(), pose.qw()),
            config.color(), config.endColor(), config.gradient());
    }
    private TimedLyrics.WordCue currentCue(TimedLyrics lyrics, long playbackMs, long durationMs) {
        // Word boundaries do not change each frame. Only the media-time phase advances.
        if (lyrics != cachedLyrics || durationMs != cachedDuration || cachedCue == TimedLyrics.WordCue.EMPTY
            || playbackMs < cachedCue.startMs() || playbackMs >= cachedCue.endMs()) {
            cachedLyrics = lyrics;
            cachedDuration = durationMs;
            cachedCue = lyrics.wordCue(playbackMs, durationMs);
        }
        return cachedCue;
    }
    private void resetAnchor() {
        anchor.reset(); clock.reset(); track = null; cachedLyrics = null; cachedCue = TimedLyrics.WordCue.EMPTY;
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
