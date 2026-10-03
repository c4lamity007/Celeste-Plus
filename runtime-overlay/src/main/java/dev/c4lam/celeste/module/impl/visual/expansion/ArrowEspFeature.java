package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.friend.FriendManager;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.gui.HudTransparentScreen;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;

/** Direction arrows for nearby players. Always drawn, including on-screen. */
public final class ArrowEspFeature implements ModuleFeature {
    private static final int MAX_ARROWS = 64;
    private static final int MAX_PLAYER_VISITS = 256;
    private static final int DEFAULT_COLOR = 0xFF4FA3;
    private static final Identifier ARROW_2_TEXTURE = Identifier.of("celeste", "textures/arrows/arrow2.png");
    private static final Identifier ARROW_3_TEXTURE = Identifier.of("celeste", "textures/arrows/arrow3.png");
    private volatile boolean enabled;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.8D;
    private volatile int arrowStyle = 0;
    private volatile int colorRgb = DEFAULT_COLOR;
    private float radius = 58.0F;
    private float markerSize = 22.0F;
    private float glow = .45F;
    private boolean distanceFade = true;
    private boolean showDistance;

    @Override
    public String name() {
        return "Arrow ESP";
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
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
    }

    public void render(MinecraftClient client, int width, int height, float tickProgress) {
        ClientPlayerEntity self = client.player;
        if (!enabled || self == null || client.world == null
            || (client.currentScreen != null && !(client.currentScreen instanceof HudTransparentScreen)) || client.options.hudHidden
            || width <= 0 || height <= 0 || !client.gameRenderer.getCamera().isReady()) {
            return;
        }

        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        Vec3d selfPosition = self.getLerpedPos(tickProgress);
        float cameraYaw = client.gameRenderer.getCamera().getYaw();
        double maxRange = range;
        double rangeSquared = maxRange * maxRange;
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        int count = 0;
        int visits = 0;

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (count >= MAX_ARROWS || visits++ >= MAX_PLAYER_VISITS) {
                break;
            }
            if (player == self || !player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.isCreative() || FriendManager.getInstance().isFriend(player)) {
                continue;
            }
            Vec3d position = player.getLerpedPos(tickProgress);
            double distanceSquared = selfPosition.squaredDistanceTo(position);
            if (distanceSquared > rangeSquared || distanceSquared < 1.0E-5D) {
                continue;
            }

            double dx = position.x - camera.x;
            double dz = position.z - camera.z;
            if (dx * dx + dz * dz < 1.0E-6D) {
                continue;
            }
            float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F - cameraYaw);
            double distance = Math.sqrt(distanceSquared);
            float distanceFactor = (float) MathHelper.clamp(distance / maxRange, 0.0D, 1.0D);
            float edge = MathHelper.clamp((1.0F - distanceFactor) / .1F, 0.0F, 1.0F);
            float edgeFade = edge * edge * (3.0F - 2.0F * edge);
            float alphaFactor = (float) (opacity * (distanceFade
                ? (1.0D - distanceFactor * 0.65D) * edgeFade : 1.0D));
            int alpha = MathHelper.clamp(Math.round(alphaFactor * 255.0F), 0, 255);
            if (alpha == 0) {
                continue;
            }
            Color color = new Color(colorRgb >> 16 & 0xFF, colorRgb >> 8 & 0xFF, colorRgb & 0xFF, alpha);
            float offset = Math.min(radius + 10.0F * distanceFactor, Math.max(12, Math.min(width, height) * .5F - markerSize));
            float size = markerSize * (1.0F - .15F * distanceFactor);
            Identifier texture = arrowStyle == 1 ? ARROW_3_TEXTURE : ARROW_2_TEXTURE;

            matrices.push();
            matrices.translate(centerX, centerY, 0.0F);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw));
            matrices.translate(0.0F, -offset, 0.0F);
            // Both bundled silhouettes point down; rotate the sprite locally so
            // its tip points outward toward the player, not back at the crosshair.
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(180.0F));
            if (glow > .001F) {
                for (int halo = 2; halo >= 1; halo--) {
                    float haloSize = size + halo * 4.5F;
                    Color haloColor = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        Math.round(alpha * glow * (halo == 2 ? .10F : .20F)));
                    RenderUtil.TEXTURE_RECT.draw(matrices, -haloSize * .5F, -haloSize * .5F,
                        haloSize, haloSize, 0, haloColor, 0, 0, 1, 1, texture);
                }
            }
            RenderUtil.TEXTURE_RECT.draw(
                matrices,
                -size / 2.0F,
                -size / 2.0F,
                size,
                size,
                0,
                color,
                0,
                0,
                1,
                1,
                texture
            );
            matrices.pop();
            if (showDistance) {
                double angle = Math.toRadians(yaw);
                float labelRadius = offset + size * .5F + 6.0F;
                float labelX = centerX + (float) Math.sin(angle) * labelRadius;
                float labelY = centerY - (float) Math.cos(angle) * labelRadius;
                String label = Math.round(distance) + "m";
                float halfLabel = Fonts.regular().width(label, 7.0F) * .5F;
                float labelHeight = Fonts.regular().height(7.0F);
                labelX = MathHelper.clamp(labelX, Math.min(centerX, halfLabel + 2.0F),
                    Math.max(centerX, width - halfLabel - 2.0F));
                labelY = MathHelper.clamp(labelY - 3.5F, 2.0F, Math.max(2.0F, height - labelHeight - 2.0F));
                Fonts.regular().drawCentered(matrices, label, labelX + .5F, labelY + .5F, 7.0F,
                    new Color(0, 0, 0, Math.round(alpha * .72F)));
                Fonts.regular().drawCentered(matrices, label,
                    labelX, labelY, 7.0F, new Color(239, 242, 248, alpha));
            }
            count++;
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp(context.slider("Range", 64.0D), 16.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", 0.8D), 0.1D, 1.0D);
        arrowStyle = "Arrow 3".equals(context.mode("Arrow Style", "Arrow 2")) ? 1 : 0;
        colorRgb = context.colorPick("Color", DEFAULT_COLOR) & 0xFFFFFF;
        radius = (float) MathHelper.clamp(context.slider("Radius", 58), 28, 120);
        markerSize = (float) MathHelper.clamp(context.slider("Size", 22), 12, 36);
        glow = (float) MathHelper.clamp(context.slider("Glow", .45), 0, 1);
        distanceFade = context.toggle("Distance fade", true);
        showDistance = context.toggle("Show distance", false);
    }
}
