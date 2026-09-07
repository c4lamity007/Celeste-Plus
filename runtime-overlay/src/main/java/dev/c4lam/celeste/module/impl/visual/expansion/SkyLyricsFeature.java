package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.media.MediaPlaybackSnapshot;
import dev.c4lam.celeste.media.SystemMediaService;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.compat.RenderCompatibility;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Quaternionf;

/** A small sky billboard synchronized to the existing Windows/Spotify playback session. */
public final class SkyLyricsFeature implements ModuleFeature {
    private volatile boolean enabled;
    private volatile MediaPlaybackSnapshot media = MediaPlaybackSnapshot.empty("");
    private float size = 1, opacity = .9f;
    private double azimuth, elevation = 30, offset;
    private boolean nextLine = true;
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
        media = MediaPlaybackSnapshot.empty("");
    }
    @Override public String name() { return "Sky Lyrics"; }
    @Override public void onEnable(ModuleContext context) {
        enabled = true; onStartTick(context);
    }
    @Override public void onDisable(ModuleContext context) {
        stop(); SkyLyricsRenderer.release();
    }
    @Override public void onWorldChanged(ModuleContext context) { media = MediaPlaybackSnapshot.empty(""); }
    @Override public void onStartTick(ModuleContext context) {
        if (!enabled) return;
        size = (float)finite(context.slider("Size", 1), 1, .5, 2);
        opacity = (float)finite(context.slider("Opacity", .9), .9, .1, 1);
        azimuth = finite(context.slider("Direction", 0), 0, -180, 180);
        elevation = finite(context.slider("Elevation", 30), 30, 15, 70);
        offset = finite(context.slider("Timing Offset", 0), 0, -5000, 5000);
        nextLine = context.toggle("Next Line", true);
        media = SystemMediaService.getInstance().snapshot();
    }
    SkyLyricsRenderer.Frame extract(WorldExtractionContext context) {
        MediaPlaybackSnapshot frameMedia = media;
        if (!enabled || !frameMedia.available() || frameMedia.lyrics().lines().isEmpty() || RenderCompatibility.isShadowPass() || context.world() == null
            || context.camera() == null || !context.camera().isReady() || MinecraftClient.getInstance().options.hudHidden
            || !context.world().getRegistryKey().equals(World.OVERWORLD)
            || context.camera().getSubmersionType() != CameraSubmersionType.NONE) return SkyLyricsRenderer.Frame.EMPTY;
        if (context.camera().getFocusedEntity() instanceof LivingEntity focused
            && (focused.hasStatusEffect(StatusEffects.BLINDNESS) || focused.hasStatusEffect(StatusEffects.DARKNESS)))
            return SkyLyricsRenderer.Frame.EMPTY;
        var cue = frameMedia.lyrics().cue(Math.max(0, frameMedia.estimatedProgressMs() + (long)offset), frameMedia.durationMs());
        if (cue.current().isBlank()) return SkyLyricsRenderer.Frame.EMPTY;
        String line = cue.current();
        String secondary = nextLine ? cue.next() : "";
        double yaw = Math.toRadians(azimuth), pitch = Math.toRadians(elevation), radius = 64;
        Vec3d relative = new Vec3d(-Math.sin(yaw) * Math.cos(pitch) * radius,
            Math.sin(pitch) * radius, Math.cos(yaw) * Math.cos(pitch) * radius);
        return new SkyLyricsRenderer.Frame(line, secondary, frameMedia.artist(), (float)cue.progress(),
            opacity * (frameMedia.playing() ? 1 : .65f), size, relative, new Quaternionf(context.camera().getRotation()));
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}