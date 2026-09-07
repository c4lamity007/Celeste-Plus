package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.config.InterfaceConfig;
import dev.c4lam.celeste.module.expansion.inventory.SilentInventoryScreen;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.render.RenderUtil;
import dev.c4lam.celeste.render.font.Fonts;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Vector4f;

import java.awt.Color;
import java.util.Locale;

/** One lightweight HUD layer shared by all passive combat guides. */
public final class CombatOverlayRenderer {
    private static final Identifier LAYER_ID = Identifier.of(CelesteClient.MOD_ID, "combat_guides");
    private static final Color TEXT = new Color(250, 244, 250, 255);
    private static final Color MUTED = new Color(178, 164, 179, 255);
    private static final Color ACCENT = new Color(255, 43, 143, 255);
    private static final Color TOP = new Color(31, 20, 32, 218);
    private static final Color BOTTOM = new Color(12, 9, 16, 232);

    private static boolean initialized;
    private static SmashGuideFeature smashGuide;
    private static DamageRenderFeature damageRender;
    private static SmashGuideFeature.Estimate lastSmash;
    private static DamageRenderFeature.Estimate lastDamage;
    private static float smashVisibility;
    private static float damageVisibility;
    private static long lastFrameNanos;
    private static Object shownWorld;
    private static String damageTarget;
    private static float targetFill;
    private static float selfFill;

    private CombatOverlayRenderer() {
    }

    public static synchronized void initialize(SmashGuideFeature smash, DamageRenderFeature damage) {
        smashGuide = smash;
        damageRender = damage;
        if (initialized) {
            return;
        }
        initialized = true;
        BetaSubscriptions.register(HudElementRegistry.class, LAYER_ID.toString(), HudElement.class,
            CombatOverlayRenderer::render, proxy -> HudElementRegistry.addLast(LAYER_ID, proxy));
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!AuthBootstrap.authorized()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world != shownWorld || client.player == null || !client.player.isAlive()) {
            shownWorld = client.world;
            lastSmash = null;
            lastDamage = null;
            smashVisibility = damageVisibility = 0;
            lastFrameNanos = 0;
            damageTarget = null;
        }
        if ((client.currentScreen != null && !(client.currentScreen instanceof SilentInventoryScreen))
            || client.options.hudHidden || client.player == null
            || !client.player.isAlive() || client.world == null) {
            lastSmash = null;
            lastDamage = null;
            smashVisibility = damageVisibility = 0;
            lastFrameNanos = 0;
            damageTarget = null;
            return;
        }

        SmashGuideFeature.Estimate currentSmash = smashGuide == null ? null : smashGuide.estimate();
        DamageRenderFeature.Estimate currentDamage = damageRender == null ? null : damageRender.estimate();
        if (currentSmash != null) {
            lastSmash = currentSmash;
        }
        if (currentDamage != null) {
            lastDamage = currentDamage;
        }

        float seconds = frameSeconds();
        if (lastDamage != null) {
            float target = DamageRenderFeature.fraction(lastDamage.targetDamage(), lastDamage.targetHealth());
            float self = DamageRenderFeature.fraction(lastDamage.selfDamage(), lastDamage.selfHealth());
            if (!lastDamage.targetName().equals(damageTarget) || damageVisibility < .008F) {
                targetFill = target;
                selfFill = self;
                damageTarget = lastDamage.targetName();
            } else {
                // Only the decorative tracks ease; numeric estimates and lethal warnings stay immediate.
                targetFill = damp(targetFill, target, 18F, seconds);
                selfFill = damp(selfFill, self, 18F, seconds);
            }
        }
        smashVisibility = damp(smashVisibility, currentSmash == null ? 0.0F : 1.0F, 15.0F, seconds);
        damageVisibility = damp(damageVisibility, currentDamage == null ? 0.0F : 1.0F, 15.0F, seconds);
        if (smashVisibility < 0.008F && damageVisibility < 0.008F) {
            return;
        }

        MatrixStack matrices = RenderUtil.matrices();
        matrices.loadIdentity();
        GuiRenderer.getInstance().clearScissors();
        float centerX = context.getScaledWindowWidth() * 0.5F;
        float centerY = context.getScaledWindowHeight() * 0.5F;
        boolean showSmash = lastSmash != null && smashVisibility >= 0.008F;
        boolean showDamage = lastDamage != null && damageVisibility >= 0.008F;
        float smashHeight = showSmash ? (lastSmash.showHeight() ? 31F : 23F) : 0F;
        float damageHeight = showDamage ? (lastDamage.detailed() ? 56F : 43F) : 0F;
        float contentHeight = smashHeight + damageHeight + (showSmash && showDamage ? 8F : 0F);
        float cardY = Math.max(4F, Math.min(centerY + (showSmash ? 31F : 69F),
            context.getScaledWindowHeight() - 48F - contentHeight));
        if (showSmash) {
            String primary = String.format(Locale.ROOT, "Smash  %.1f", lastSmash.damage());
            String secondary = lastSmash.showHeight()
                ? String.format(Locale.ROOT, "%.1f blocks fallen", lastSmash.fallDistance()) : "";
            drawCard(matrices, centerX, cardY, primary, secondary, smashVisibility);
            cardY += smashHeight + 8F;
        }
        if (showDamage) {
            DamageRenderFeature.drawEstimate(matrices, centerX, cardY, lastDamage, damageVisibility,
                targetFill, selfFill);
        }
    }

    private static void drawCard(MatrixStack matrices, float centerX, float y, String primary,
                                 String secondary, float opacity) {
        boolean twoLines = !secondary.isEmpty();
        float textWidth = Math.max(Fonts.semibold().width(primary, 7.4F),
            twoLines ? Fonts.regular().width(secondary, 5.8F) : 0.0F);
        float width = Math.max(88.0F, Math.min(180.0F, textWidth + 25.0F));
        float height = twoLines ? 31.0F : 23.0F;
        float x = centerX - width * 0.5F;
        float shownY = y + (1.0F - opacity) * 4.0F;
        float scale = 0.94F + opacity * 0.06F;

        matrices.push();
        matrices.translate(centerX, shownY + height * 0.5F, 0.0F);
        matrices.scale(scale, scale, 1.0F);
        matrices.translate(-centerX, -(shownY + height * 0.5F), 0.0F);

        // Reuse the HUD's single world-blur capture. BlurRectRender falls back
        // to this same tint when no capture exists, so guides never disappear.
        RenderUtil.BLUR_RECT.draw(matrices, x, shownY, width, height, new Vector4f(7.5F),
            alpha(TOP, opacity), alpha(TOP, opacity), alpha(BOTTOM, opacity), alpha(BOTTOM, opacity),
            InterfaceConfig.glassTintMix());
        RenderUtil.RECT.draw(matrices, centerX - 8.0F, shownY + 2.5F, 16.0F, 1.4F, 0.7F,
            alpha(ACCENT, opacity * 0.82F));

        float primaryY = twoLines ? shownY + 6.0F : shownY + 7.0F;
        Fonts.semibold().drawCenteredText(matrices, primary, centerX, primaryY, 7.4F, alpha(TEXT, opacity));
        if (twoLines) {
            Fonts.regular().drawCenteredText(matrices, secondary, centerX, shownY + 18.5F, 5.8F,
                alpha(MUTED, opacity));
        }
        matrices.pop();
    }

    private static float frameSeconds() {
        long now = System.nanoTime();
        float seconds = lastFrameNanos == 0L ? 0F
            : Math.max(0.0F, (now - lastFrameNanos) / 1_000_000_000.0F);
        lastFrameNanos = now;
        return seconds;
    }

    private static float damp(float current, float target, float speed, float seconds) {
        return current + (target - current) * (1.0F - (float) Math.exp(-speed * seconds));
    }

    private static Color alpha(Color color, float opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
            Math.max(0, Math.min(255, Math.round(color.getAlpha() * opacity))));
    }

    private static String trim(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maximum - 3)) + "...";
    }
}
