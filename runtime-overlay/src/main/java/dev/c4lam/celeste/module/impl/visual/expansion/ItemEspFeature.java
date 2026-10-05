package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Box ESP plus a vanilla model-silhouette highlight; never mutates entity/team state. */
public final class ItemEspFeature implements ModuleFeature {
    private static final int MAX_ITEMS = 96;
    private static final Comparator<Target> NEAREST = Comparator.comparingDouble(Target::distanceSquared)
        .thenComparingInt(Target::id);
    private static final Selection EMPTY = new Selection(null, List.of());
    private static volatile ItemEspFeature active;

    private volatile boolean enabled;
    private volatile ItemEspStyle style = ItemEspStyle.ITEM_HIGHLIGHT;
    private volatile String theme = "Celeste";
    private volatile int customColor = 0xF09BD0;
    private volatile double range = 64.0D;
    private volatile double opacity = 0.76D;
    private volatile Selection selection = EMPTY;

    @Override public String name() { return "Item ESP"; }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        selection = EMPTY;
        enabled = true;
        active = this;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        update(context);
        enabled = true;
        active = this;
        selectLoadedItems(context.client());
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        selection = EMPTY;
        if (active == this) active = null;
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        enabled = false;
        selection = EMPTY;
        if (active == this) active = null;
    }

    /** Query Minecraft's existing typed spatial index once per game tick, never per frame. */
    private void selectLoadedItems(MinecraftClient client) {
        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        if (!client.isOnThread() || !style.showsBox() || self == null || world == null
            || self.getEntityWorld() != world) {
            selection = EMPTY;
            return;
        }
        double radius = range, radiusSquared = radius * radius;
        Vec3d origin = self.getEntityPos();
        Box search = new Box(origin.x - radius, origin.y - radius, origin.z - radius,
            origin.x + radius, origin.y + radius, origin.z + radius);
        List<ItemEntity> loaded = world.getEntitiesByClass(ItemEntity.class, search,
            item -> eligible(item, world) && origin.squaredDistanceTo(item.getEntityPos()) <= radiusSquared);
        // Keep the existing render cap while selecting the actual nearest items regardless
        // of entity-index iteration order. No sorting of the entire loaded-item population.
        PriorityQueue<Target> nearest = new PriorityQueue<>(MAX_ITEMS, NEAREST.reversed());
        for (ItemEntity item : loaded) {
            double distanceSquared = origin.squaredDistanceTo(item.getEntityPos());
            int id = item.getId();
            if (nearest.size() == MAX_ITEMS) {
                Target furthest = nearest.peek();
                int order = Double.compare(distanceSquared, furthest.distanceSquared);
                if (order > 0 || order == 0 && id >= furthest.id) continue;
                nearest.poll();
            }
            nearest.add(new Target(item, distanceSquared, id));
        }
        List<Target> ordered = new ArrayList<>(nearest);
        ordered.sort(NEAREST);
        List<ItemEntity> items = new ArrayList<>(ordered.size());
        for (Target target : ordered) items.add(target.item);
        selection = new Selection(world, List.copyOf(items));
    }

    private static boolean eligible(ItemEntity item, ClientWorld world) {
        return item != null && item.getEntityWorld() == world && item.isAlive() && !item.isRemoved()
            && !item.getStack().isEmpty();
    }

    private record Target(ItemEntity item, double distanceSquared, int id) { }
    private record Selection(ClientWorld world, List<ItemEntity> items) { }

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
        Selection selected = selection;
        if (!enabled || !style.showsBox() || self == null || self.getEntityWorld() != context.world()
            || selected.world != context.world()) return;

        float tickProgress = context.tickCounter().getTickProgress(false);
        Vec3d viewer = self.getLerpedPos(tickProgress);
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        for (ItemEntity item : selected.items) {
            if (!eligible(item, selected.world)) continue;
            Vec3d interpolated = item.getLerpedPos(tickProgress);
            double distance = Math.sqrt(viewer.squaredDistanceTo(interpolated));
            double fade = MathHelper.clamp((range - distance) / Math.max(3.0D, range * .15D), 0.0D, 1.0D);
            fade = fade * fade * (3.0D - 2.0D * fade);
            float alpha = (float) MathHelper.clamp(opacity * fade, 0.0D, 1.0D);
            if (alpha <= .003F) continue;
            Box bounds = item.getBoundingBox().offset(interpolated.subtract(item.getEntityPos())).expand(.045D);
            if (context.frustum() != null && !context.frustum().isVisible(bounds)) continue;
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
