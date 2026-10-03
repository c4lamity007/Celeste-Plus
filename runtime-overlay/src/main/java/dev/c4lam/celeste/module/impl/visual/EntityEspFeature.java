package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.render.geometry.WorldBox;
import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.EntityEspPlayerEchoRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Comparator;

/** Posed, shader-lit player silhouettes; no glowing, team, entity, or scoreboard mutations. */
public final class EntityEspFeature implements ModuleFeature {
    private static final int CELESTE_PINK = 0xFF4FA3;
    private static final int MAX_CHAMS = 12;
    private final EntityEspPlayerEchoRenderer modelSnapshots = new EntityEspPlayerEchoRenderer();

    private volatile boolean enabled;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.85D;
    private volatile double fillOpacity = 0.07D;
    private volatile float lineWidth = 1.65F;
    private volatile boolean corners = true;
    private volatile boolean glow = true;
    private volatile boolean pulse;
    private volatile String colorMode = "Celeste";
    private volatile int colorRgb = CELESTE_PINK;

    @Override
    public String name() {
        return "Entity ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
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
        modelSnapshots.clear();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        modelSnapshots.clear();
    }

    public void extract(WorldExtractionContext context, List<WorldBox> output,
                 List<EntityEspPlayerEchoRenderer.Echo> chams) {
        if (!enabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity self = client.player;
        if (self == null || !self.isAlive() || self.isRemoved() || self.getEntityWorld() != context.world()) {
            return;
        }

        double renderRange = range;
        double rangeSquared = renderRange * renderRange;
        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d selfPosition = self.getLerpedPos(tickProgress);
        double animation = pulse ? 0.92D + Math.sin(Util.getMeasuringTimeMs() * 0.0024D) * 0.08D : 1.0D;
        double fadeWidth = Math.min(10.0D, Math.max(2.0D, renderRange * 0.15D));

        // A fixed nearby mesh budget keeps a busy spawn from multiplying model submissions.
        List<AbstractClientPlayerEntity> players = context.world().getPlayers().stream()
            .sorted(Comparator.comparingDouble((AbstractClientPlayerEntity player) -> self.squaredDistanceTo(player))
                .thenComparingInt(AbstractClientPlayerEntity::getId)).toList();
        int captured = 0;
        for (AbstractClientPlayerEntity player : players) {
            if (player == self
                || !player.isAlive()
                || player.isRemoved()
                || player.isSpectator()
                || player.isCreative()
                || FriendManager.getInstance().isFriend(player)) {
                continue;
            }

            Vec3d interpolated = player.getLerpedPos(tickProgress);
            double distanceSquared = selfPosition.squaredDistanceTo(interpolated);
            if (distanceSquared > rangeSquared) {
                continue;
            }

            double edge = MathHelper.clamp(
                (renderRange - Math.sqrt(distanceSquared)) / fadeWidth,
                0.0D,
                1.0D
            );
            edge = edge * edge * (3.0D - 2.0D * edge);
            float lineAlpha = (float) MathHelper.clamp(opacity * animation * edge, 0.0D, 1.0D);
            if (lineAlpha <= 0.002F) {
                continue;
            }

            Box bounds = player.getBoundingBox().offset(interpolated.subtract(player.getEntityPos())).expand(0.015D);
            int rgb = "Health".equals(colorMode)
                ? healthColor(player.getHealth() / Math.max(1.0F, player.getMaxHealth()))
                : "Custom".equals(colorMode) ? colorRgb : CELESTE_PINK;
            float fillAlpha = (float) (fillOpacity * edge * animation);
            boolean posed = false;
            if (glow && !Boolean.TRUE.equals(dev.c4lam.celeste.module.delivery.ModuleHooks.invoke("chams", "chams.active", false)) && captured < MAX_CHAMS && distanceSquared <= 48.0D * 48.0D
                && (context.frustum() == null || context.frustum().isVisible(bounds.expand(0.15D)))) {
                float poseProgress = context.tickCounter().getTickProgress(
                    !context.world().getTickManager().shouldSkipTick(player));
                var mesh = modelSnapshots.snapshot(player, poseProgress);
                if (mesh != null) {
                    int accent = "Celeste".equals(colorMode) ? 0x92C8FF : mix(rgb, 0xE8F7FF, 0.38F);
                    EntityEspPlayerEchoRenderer.Echo echo = new EntityEspPlayerEchoRenderer.Echo(mesh, mesh.position(),
                        mesh.playerYaw(), rgb, accent,
                        Math.min(0.70F, lineAlpha * 0.45F + fillAlpha), 0.0F);
                    if (EntityEspPlayerEchoRenderer.visible(context.frustum(), context.camera().getCameraPos(), echo)) {
                        chams.add(echo);
                        captured++;
                        posed = true;
                    }
                }
            }
            output.add(new WorldBox(
                bounds,
                WorldBox.argb(posed ? 0.0F : fillAlpha, rgb),
                WorldBox.argb(posed ? 0.0F : fillAlpha * 0.30F, rgb),
                WorldBox.argb(lineAlpha * (posed ? 0.52F : 1.0F), rgb),
                lineWidth,
                corners ? WorldBox.Shape.CORNERS : WorldBox.Shape.BOX,
                false
            ));
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.85D), 0.1D, 1.0D);
        fillOpacity = MathHelper.clamp(context.slider("Fill opacity", 0.07D), 0.0D, 0.35D);
        lineWidth = (float) MathHelper.clamp(context.slider("Line width", 1.65D), 0.75D, 3.0D);
        corners = "Corners".equals(context.mode("Style", "Corners"));
        glow = context.toggle("Glow", true);
        pulse = context.toggle("Pulse", false);
        colorMode = context.mode("Color mode", "Celeste");
        colorRgb = context.colorPick("Color", CELESTE_PINK) & 0xFFFFFF;
    }

    private static int healthColor(float health) {
        float ratio = MathHelper.clamp(health, 0.0F, 1.0F);
        int low = ratio < 0.5F ? 0xFF5B79 : 0xFFD476;
        int high = ratio < 0.5F ? 0xFFD476 : 0x77E3B4;
        float amount = ratio < 0.5F ? ratio * 2.0F : (ratio - 0.5F) * 2.0F;
        int red = Math.round(MathHelper.lerp(amount, low >> 16 & 255, high >> 16 & 255));
        int green = Math.round(MathHelper.lerp(amount, low >> 8 & 255, high >> 8 & 255));
        int blue = Math.round(MathHelper.lerp(amount, low & 255, high & 255));
        return red << 16 | green << 8 | blue;
    }

    private static int mix(int first, int second, float amount) {
        int red = Math.round(MathHelper.lerp(amount, first >> 16 & 255, second >> 16 & 255));
        int green = Math.round(MathHelper.lerp(amount, first >> 8 & 255, second >> 8 & 255));
        int blue = Math.round(MathHelper.lerp(amount, first & 255, second & 255));
        return red << 16 | green << 8 | blue;
    }
}
