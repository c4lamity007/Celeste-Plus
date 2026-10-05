package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.hud.HudTargetTracker;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Exact hitbox or compact, frame-animated objects around the selected target. */
public final class TargetEspFeature implements ModuleFeature {
    private static final int MAX_EFFECT_QUADS = TargetOrbitMesh.MAX_QUADS;
    private volatile boolean enabled;
    private volatile String style = "Box";
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xFF4FA3;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.86D;
    private volatile double speed = 1.0D;
    private volatile double radius = 1.0D;
    private volatile double glow = 0.65D;
    private int acquiredId = -1;
    private long acquiredAt;
    private long lastSeenAt;

    @Override
    public String name() {
        return "Target ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        acquiredId = -1;
        enabled = true;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        acquiredId = -1;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        acquiredId = -1;
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines) {
        extract(context, boxes, lines, null);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows) {
        extract(context, boxes, lines, glows, 768);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> boxes,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows, int quadLimit) {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (!enabled || self == null || self.getEntityWorld() != context.world()) return;
        long now = Util.getMeasuringTimeMs();
        LivingEntity target = select(self);
        double exit = 1.0D;
        if (target == null) {
            // Retain only the id for a short fade; resolve against this world each frame.
            if (now - lastSeenAt < 220L && context.world().getEntityById(acquiredId) instanceof LivingEntity cached
                && valid(self, cached)) {
                target = cached;
                exit = 1.0D - (now - lastSeenAt) / 220.0D;
                exit *= exit;
            } else {
                acquiredId = -1;
                return;
            }
        } else {
            lastSeenAt = now;
        }

        if (target.getId() != acquiredId) {
            acquiredId = target.getId();
            acquiredAt = now;
        }
        double enter = MathHelper.clamp((now - acquiredAt) / 180.0D, 0.0D, 1.0D);
        enter = enter * enter * (3.0D - 2.0D * enter);
        double time = now / 1000.0D;
        double phase = time * speed * 1.8D;
        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d interpolated = target.getLerpedPos(tickProgress);
        double distance = self.getLerpedPos(tickProgress).distanceTo(interpolated);
        double distanceFade = MathHelper.clamp((range - distance) / 4.0D, 0.0D, 1.0D);
        double alpha = opacity * enter * exit * distanceFade * (0.94D + 0.06D * Math.sin(phase * 1.2D));
        Box bounds = target.getBoundingBox().offset(interpolated.subtract(target.getEntityPos()));
        Vec3d center = bounds.getCenter();
        int color = VisualEffects.color(theme, customColor, time, 0.0D);
        if (glows == null) {
            // Compatibility extraction has no material batch; keep a simple
            // target box there, never a hairline fallback in the actual path.
            boxes.add(new VisualExpansionRenderer.WorldBox(bounds,
                VisualExpansionRenderer.argb((float) (alpha * 0.045D), color),
                VisualExpansionRenderer.argb((float) (alpha * 0.85D), color), 1.4F));
            return;
        }
        int available = Math.max(0, Math.min(Math.min(768, quadLimit) - glows.size(), MAX_EFFECT_QUADS));
        int main = VisualExpansionRenderer.argb((float) (alpha * (.78D + glow * .16D)), color);
        int accent = VisualExpansionRenderer.argb((float) (alpha * (.70D + glow * .16D)),
            VisualEffects.color(theme, customColor, time, .45D));
        List<SolidVisualMesh.Face> mesh = "Box".equals(style)
            ? TargetOrbitMesh.box(bounds.getLengthX(), bounds.getLengthZ(), bounds.getLengthY(), phase,
                VisualExpansionRenderer.argb((float) (alpha * .34D), color),
                VisualExpansionRenderer.argb((float) (alpha * .28D), color), main, available)
            : TargetOrbitMesh.build(style, bounds.getLengthX(), bounds.getLengthZ(), bounds.getLengthY(),
                radius, phase, main, accent, available);
        Vec3d origin = new Vec3d(center.x, bounds.minY, center.z);
        WorldGlowRenderer.Texture material = "Box".equals(style) ? WorldGlowRenderer.Texture.PRISM : WorldGlowRenderer.Texture.VOLUME;
        for (SolidVisualMesh.Face face : mesh) {
            glows.add(new WorldGlowRenderer.Quad(material, point(origin, face.a()), point(origin, face.b()),
                point(origin, face.c()), point(origin, face.d()), face.color(), face.endColor(), face.uStart(), face.uEnd()));
        }
    }

    private static Vec3d point(Vec3d origin, SolidVisualMesh.Point point) {
        return origin.add(point.x(), point.y(), point.z());
    }

    private LivingEntity select(ClientPlayerEntity self) {
        LivingEntity target = HudTargetTracker.getInstance().activeTarget(MinecraftClient.getInstance());
        return target != null && valid(self, target) ? target : null;
    }

    private boolean valid(ClientPlayerEntity self, LivingEntity target) {
        return target != self && target.getEntityWorld() == self.getEntityWorld()
            && target.isAlive() && !target.isRemoved() && !target.isSpectator()
            && !(target instanceof AbstractClientPlayerEntity player && FriendManager.getInstance().isFriend(player))
            && self.squaredDistanceTo(target) <= range * range;
    }

    private void update(ModuleContext context) {
        String selected = context.mode("Style", "Box");
        style = switch (selected) { case "Crystals", "Cubes", "Sparks", "Box" -> selected; default -> "Box"; };
        theme = context.mode("Theme", "Celeste");
        customColor = context.colorPick("Color", 0xFF4FA3) & 0xFFFFFF;
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.86D), 0.1D, 1.0D);
        speed = MathHelper.clamp(context.slider("Speed", 1.0D), 0.2D, 3.0D);
        radius = MathHelper.clamp(context.slider("Radius", 1.0D), 0.7D, 1.6D);
        glow = MathHelper.clamp(context.slider("Glow", 0.65D), 0.0D, 1.0D);
    }
}
