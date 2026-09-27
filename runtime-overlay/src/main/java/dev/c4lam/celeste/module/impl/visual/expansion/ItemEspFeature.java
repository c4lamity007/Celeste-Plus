package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Box ESP plus a vanilla model-silhouette highlight; never mutates entity/team state. */
public final class ItemEspFeature implements ModuleFeature {
    private static final int MAX_ITEMS = 96;
    private static final int MAX_ENTITY_VISITS = 1_024;
    private static volatile ItemEspFeature active;

    private volatile boolean enabled;
    private volatile ItemEspStyle style = ItemEspStyle.ITEM_HIGHLIGHT;
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xF09BD0;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.76D;

    @Override public String name() { return "Item ESP"; }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
        active = this;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
        active = this;
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        if (active == this) active = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        if (active == this) active = null;
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines) {
        extract(context, output, lines, null);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows) {
        extract(context, output, lines, glows, 768);
    }

    void extract(WorldExtractionContext context, List<VisualExpansionRenderer.WorldBox> output,
                 List<VisualExpansionRenderer.WorldLineStrip> lines, List<WorldGlowRenderer.Quad> glows,
                 int quadLimit) {
        ClientPlayerEntity self = MinecraftClient.getInstance().player;
        if (!enabled || !style.showsBox() || self == null || self.getEntityWorld() != context.world()) return;

        double rangeSquared = range * range;
        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d viewer = self.getLerpedPos(tickProgress);
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        int visits = 0;
        List<ItemEntity> candidates = new ArrayList<>();
        for (var entity : context.world().getEntities()) {
            if (visits++ >= MAX_ENTITY_VISITS) break;
            if (entity instanceof ItemEntity item && item.isAlive() && !item.isRemoved()
                && !item.getStack().isEmpty() && self.squaredDistanceTo(item) <= rangeSquared) {
                candidates.add(item);
            }
        }
        candidates.sort(Comparator.comparingDouble((ItemEntity item) -> self.squaredDistanceTo(item))
            .thenComparingInt(ItemEntity::getId));

        int count = 0;
        for (ItemEntity item : candidates) {
            if (count++ >= MAX_ITEMS) break;
            Vec3d interpolated = item.getLerpedPos(tickProgress);
            double distance = Math.sqrt(viewer.squaredDistanceTo(interpolated));
            double fade = MathHelper.clamp((range - distance) / Math.max(3.0D, range * .15D), 0.0D, 1.0D);
            fade = fade * fade * (3.0D - 2.0D * fade);
            float alpha = (float) MathHelper.clamp(opacity * fade, 0.0D, 1.0D);
            if (alpha <= .003F) continue;
            Box bounds = item.getBoundingBox().offset(interpolated.subtract(item.getEntityPos())).expand(.045D);
            double seed = Math.floorMod(item.getId(), 61) / 61.0D;
            int color = VisualEffects.color(theme, customColor, time, seed * .2D);
            output.add(new VisualExpansionRenderer.WorldBox(bounds,
                VisualExpansionRenderer.argb(alpha * .045F, color),
                VisualExpansionRenderer.argb(alpha * .80F, color), 1.15F));
        }
    }

    /** Applies Minecraft's actual item-model outline pass, not a bounding-box substitute. */
    public static void applyModelHighlight(ItemEntity entity, ItemEntityRenderState state) {
        ItemEspFeature feature = active;
        if (feature == null || !feature.enabled || entity == null || state == null
            || !feature.style.showsHighlight() || !entity.isAlive() || entity.isRemoved()
            || entity.getStack().isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || player.getEntityWorld() != entity.getEntityWorld()
            || player.squaredDistanceTo(entity) > feature.range * feature.range) return;
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        double seed = Math.floorMod(entity.getId(), 61) / 61.0D;
        int rgb = VisualEffects.color(feature.theme, feature.customColor, time, seed * .2D);
        state.outlineColor = ColorHelper.fullAlpha(rgb);
    }

    private void update(ModuleContext context) {
        style = ItemEspStyle.from(context.mode("Style", "Item Highlight"));
        theme = context.mode("Theme", "Celeste");
        customColor = context.colorPick("Color", 0xF09BD0) & 0xFFFFFF;
        range = MathHelper.clamp(context.slider("Range", 64.0D), 8.0D, 128.0D);
        opacity = MathHelper.clamp(context.slider("Opacity", .76D), .1D, 1.0D);
    }
}
