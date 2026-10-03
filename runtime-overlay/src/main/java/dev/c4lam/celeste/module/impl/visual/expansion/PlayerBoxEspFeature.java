package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/** Six image-shaded faces per player: a real box, independent of model chams. */
public final class PlayerBoxEspFeature implements ModuleFeature {
    private static final int MAX_PLAYERS = 24;
    private static final int SHARED_QUAD_LIMIT = 768;
    private volatile boolean enabled;
    private volatile double range = 64.0;
    private volatile double opacity = 0.72;
    private volatile String theme = "Ice";
    private volatile int color = 0x74DFFF;

    @Override public String name() { return "Player ESP"; }

    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64), 8, 128);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.72), 0.1, 1);
        theme = context.mode("Theme", "Ice");
        color = context.colorPick("Color", 0x74DFFF) & 0xFFFFFF;
    }

    public void extract(WorldExtractionContext context, List<PlayerEspWorldGlowRenderer.Quad> quads) {
        extract(context, quads, SHARED_QUAD_LIMIT);
    }

    public void extract(WorldExtractionContext context, List<PlayerEspWorldGlowRenderer.Quad> quads, int quadLimit) {
        var client = MinecraftClient.getInstance();
        var self = client.player;
        if (!enabled || self == null || self.isRemoved() || self.getEntityWorld() != context.world()) return;
        float progress = context.tickCounter().getTickProgress(false);
        Vec3d origin = self.getLerpedPos(progress);
        Vec3d camera = context.camera().getCameraPos();
        double rangeSquared = range * range;
        double time = Util.getMeasuringTimeMs() / 1000.0;
        int primary = PlayerEspVisualEffects.color(theme, color, time, 0);
        int accent = PlayerEspVisualEffects.color(theme, color, time, 0.45);
        // Filter before sorting. Only nearby visible candidates allocate boxes, never remote entities.
        List<AbstractClientPlayerEntity> players = context.world().getPlayers().stream()
            .filter(player -> player != self && !player.isRemoved() && !player.isSpectator()
                && !player.isInvisible() && !FriendManager.getInstance().isFriend(player)
                && player.squaredDistanceTo(self) <= rangeSquared)
            .sorted(Comparator.comparingDouble((AbstractClientPlayerEntity p) -> p.squaredDistanceTo(self))
                .thenComparingInt(AbstractClientPlayerEntity::getId)).toList();
        int rendered = 0;
        for (AbstractClientPlayerEntity player : players) {
            if (rendered >= MAX_PLAYERS || quads.size() + 6 > Math.min(SHARED_QUAD_LIMIT, quadLimit)) break;
            float poseProgress = context.tickCounter().getTickProgress(
                !context.world().getTickManager().shouldSkipTick(player));
            Vec3d position = player.getLerpedPos(poseProgress);
            Box box = player.getBoundingBox().offset(position.subtract(player.getEntityPos())).expand(0.035);
            if (box.contains(camera) || context.frustum() != null && !context.frustum().isVisible(box)) continue;
            double distance = origin.distanceTo(position);
            double fade = MathHelper.clamp((range - distance) / Math.min(8.0, range * 0.2), 0, 1);
            fade = fade * fade * (3 - 2 * fade);
            // Keep close-up boxes from washing out the screen without shrinking the actual bounds.
            fade *= MathHelper.clamp(camera.distanceTo(box.getCenter()) / 1.2, 0, 1);
            if (fade * opacity < 0.01) continue;
            addBox(quads, box, dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (opacity * fade), primary),
                dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (opacity * fade), accent));
            rendered++;
        }
    }

    static void addBox(List<PlayerEspWorldGlowRenderer.Quad> output, Box b, int color, int accent) {
        Vec3d a = new Vec3d(b.minX, b.minY, b.minZ);
        Vec3d c = new Vec3d(b.maxX, b.minY, b.minZ);
        Vec3d d = new Vec3d(b.maxX, b.minY, b.maxZ);
        Vec3d e = new Vec3d(b.minX, b.minY, b.maxZ);
        Vec3d f = new Vec3d(b.minX, b.maxY, b.minZ);
        Vec3d g = new Vec3d(b.maxX, b.maxY, b.minZ);
        Vec3d h = new Vec3d(b.maxX, b.maxY, b.maxZ);
        Vec3d i = new Vec3d(b.minX, b.maxY, b.maxZ);
        face(output, a, c, g, f, color, accent);
        face(output, d, e, i, h, color, accent);
        face(output, e, a, f, i, color, accent);
        face(output, c, d, h, g, color, accent);
        face(output, f, g, h, i, accent, color);
        face(output, e, d, c, a, accent, color);
    }

    private static void face(List<PlayerEspWorldGlowRenderer.Quad> output, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                             int primary, int accent) {
        output.add(new PlayerEspWorldGlowRenderer.Quad(PlayerEspWorldGlowRenderer.Texture.PRISM, a, b, c, d, primary, accent));
    }
}
