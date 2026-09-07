package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.module.impl.combat.AnchorAuraFeature;
import dev.c4lam.celeste.module.expansion.inventory.ReBuffNotifierFeature;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Safe extraction/draw bridge shared only by the visual expansion modules. */
public final class VisualExpansionRenderer {
    private static final RenderLayer XRAY_QUADS = RenderLayer.of(
        "celeste_visual_expansion_quads",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_quads"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )).translucent().build()
    );
    private static final RenderLayer XRAY_LINES = RenderLayer.of(
        "celeste_visual_expansion_lines",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_lines"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )).translucent().build()
    );
    private static final RenderStateDataKey<Frame> FRAME_KEY =
        RenderStateDataKey.create(() -> "celeste_visual_expansion");
    private static boolean initialized;

    private VisualExpansionRenderer() {
    }

    static synchronized void initialize(
        TrajectoriesFeature trajectories,
        OreEspExpansionFeature ores,
        ArrowEspFeature arrows,
        ItemEspFeature items,
        TargetEspFeature targets,
        JumpCirclesFeature jumps,
        PlayerBoxEspFeature players,
        ChamsFeature chams,
        BlockHighlightFeature blockHighlight,
        AmbientEffectsFeature ambient,
        SkyLyricsFeature lyrics
    ) {
        if (initialized) return;
        initialized = true;
        WorldGlowRenderer.initialize();
        UnifiedChamsRenderer.initialize();
        AmbientEffectsRenderer.initialize();

        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:visual_expansion/extract",
            WorldRenderEvents.EndExtraction.class, context -> {
            if (!AuthBootstrap.authorized() || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) {
                return;
            }
            var worldState = context.worldState();
            if (worldState == null) {
                return;
            }

            List<WorldBox> boxes = new ArrayList<>();
            List<WorldLineStrip> lines = new ArrayList<>();
            List<WorldGlowRenderer.Quad> glows = new ArrayList<>();
            List<WorldGlowRenderer.Quad> blockMarkers = new ArrayList<>();
            blockHighlight.extract(context, blockMarkers, 192);
            var anchorPlan = AnchorAuraFeature.preview();
            long previewAge = anchorPlan == null ? -1 : Util.getMeasuringTimeMs() - anchorPlan.sampledAtMillis();
            if (anchorPlan != null && anchorPlan.world() == context.world()
                && previewAge >= 0 && previewAge <= 500) {
                var position = anchorPlan.position();
                SolidVisuals.faces(blockMarkers, BlockHighlightMesh.materialize(ANCHOR_PREVIEW_SHAPE,
                    position.getX(), position.getY(), position.getZ(), 0x58D5A4FF, 6), 198);
            }
            // Reserve jump effects first so busy ESP scenes cannot starve their short lifetime.
            jumps.extract(context, glows);
            // Absolute ceilings reserve complete geometry for later effects at saturation.
            // Earlier unused space remains available; each module keeps its normal detail cap.
            // After target: at least 24 player / 96 trajectory / 16 ore / 48 loot / 128 lag quads.
            targets.extract(context, boxes, lines, glows, 456);
            players.extract(context, glows, 480);
            trajectories.extract(context, lines, boxes, glows, 576);
            ores.extract(context, boxes, lines, glows, 592);
            items.extract(context, boxes, lines, glows, 640);
            PlayerEchoRenderer.Frame echoes = FakeLagVisualRenderer.extract(context, boxes, lines, glows, 768);
            PlayerEchoRenderer.Frame silhouettes = chams.extract(context);
            AmbientEffectsRenderer.Frame atmosphere = ambient.extract(context);
            SkyLyricsRenderer.Frame lyricFrame = lyrics.extract(context);
            FakeGambleFeature.extract(context, boxes, lines);
            worldState.setData(
                FRAME_KEY,
                boxes.isEmpty() && lines.isEmpty() && glows.isEmpty() && echoes.echoes().isEmpty()
                    && silhouettes.echoes().isEmpty() && blockMarkers.isEmpty() && atmosphere.isEmpty()
                    && lyricFrame == SkyLyricsRenderer.Frame.EMPTY
                    ? Frame.EMPTY
                    : new Frame(List.copyOf(boxes), List.copyOf(lines), List.copyOf(glows), echoes, silhouettes,
                        List.copyOf(blockMarkers), atmosphere, lyricFrame)
            );
        }, WorldRenderEvents.END_EXTRACTION::register);
        // AFTER_ENTITIES runs inside the prepared draw pass; BEFORE_DEBUG_RENDER
        // can expose an unprepared or stale WorldRenderContext in Fabric 16.2.x.
        BetaSubscriptions.register(WorldRenderEvents.AFTER_ENTITIES, "celeste:visual_expansion/draw",
            WorldRenderEvents.AfterEntities.class, VisualExpansionRenderer::draw, WorldRenderEvents.AFTER_ENTITIES::register);
        BetaSubscriptions.register(HudElementRegistry.class, "celeste:arrow_esp", HudElement.class, (context, tickCounter) -> {
            if (!AuthBootstrap.authorized()) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            arrows.render(client, context.getScaledWindowWidth(), context.getScaledWindowHeight(), tickCounter.getTickProgress(false));
            ReBuffNotifierFeature.renderHud(client, context.getScaledWindowWidth(), context.getScaledWindowHeight());
            FakeGambleFeature.renderHud(client, context.getScaledWindowWidth(), context.getScaledWindowHeight());
        }, proxy -> HudElementRegistry.addLast(Identifier.of(CelesteClient.MOD_ID, "arrow_esp"), proxy));
    }

    static int argb(float alpha, int rgb) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);
        return a << 24 | rgb & 0x00FFFFFF;
    }

    private static void draw(WorldRenderContext context) {
        if (dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
        if (!AuthBootstrap.authorized()) {
            return;
        }
        var worldState = context.worldState();
        MatrixStack matrices = context.matrices();
        var consumers = context.consumers();
        if (worldState == null || matrices == null || consumers == null
            || worldState.cameraRenderState == null || !worldState.cameraRenderState.initialized) {
            return;
        }

        Frame frame = worldState.getData(FRAME_KEY);
        if (frame == null || frame == Frame.EMPTY) return;
        Vec3d camera = worldState.cameraRenderState.pos;
        if (camera == null) return;

        AmbientEffectsRenderer.draw(context, frame.atmosphere());
        SkyLyricsRenderer.draw(context, frame.lyrics());

        // These custom layers use the provider's shared fallback allocator. Requesting
        // a different layer ends the current BufferBuilder, so finish every quad before
        // acquiring the line consumer (otherwise `fills` is no longer building).
        VertexConsumer fills = consumers.getBuffer(XRAY_QUADS);
        for (WorldBox box : frame.boxes()) {
            if ((box.fillColor() >>> 24) != 0) drawFill(matrices, fills, box.box(), camera, box.fillColor());
        }

        VertexConsumer lines = consumers.getBuffer(XRAY_LINES);
        for (WorldBox box : frame.boxes()) {
            if ((box.lineColor() >>> 24) != 0) drawOutline(matrices, lines, box.box(), camera, box.lineColor(), box.lineWidth());
        }
        for (WorldLineStrip strip : frame.lineStrips()) {
            drawLineStrip(matrices, lines, strip, camera);
        }
        // Volume-only batches do not switch provider layers before compositing.
        // Finish our lines first, without flushing unrelated world renderers.
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(XRAY_LINES);
        }
        WorldGlowRenderer.draw(context, frame.glows());
        UnifiedVolumeRenderer.draw(context, frame.blockMarkers(), true);
        UnifiedChamsRenderer.draw(context, frame.echoes(), true);
        UnifiedChamsRenderer.draw(context, frame.chams());
    }

    private static void drawLineStrip(MatrixStack matrices, VertexConsumer consumer, WorldLineStrip strip, Vec3d camera) {
        List<Vec3d> points = strip.points();
        MatrixStack.Entry entry = matrices.peek();
        for (int index = 1; index < points.size(); index++) {
            Vec3d from = points.get(index - 1);
            Vec3d to = points.get(index);
            Vec3d normal = to.subtract(from);
            double length = normal.length();
            if (length <= 1.0E-6D) continue;
            normal = normal.multiply(1.0D / length);
            line(
                consumer, entry,
                (float) (from.x - camera.x), (float) (from.y - camera.y), (float) (from.z - camera.z),
                (float) (to.x - camera.x), (float) (to.y - camera.y), (float) (to.z - camera.z),
                (float) normal.x, (float) normal.y, (float) normal.z,
                strip.color(), strip.width()
            );
        }
    }

    private static void drawFill(MatrixStack matrices, VertexConsumer consumer, Box box, Vec3d camera, int color) {
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);
        quad(consumer, entry, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, color);
        quad(consumer, entry, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, color);
        quad(consumer, entry, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1, color);
        quad(consumer, entry, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2, color);
        quad(consumer, entry, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1, color);
        quad(consumer, entry, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2, color);
    }

    private static void quad(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float ax, float ay, float az, float bx, float by, float bz,
        float cx, float cy, float cz, float dx, float dy, float dz, int color
    ) {
        consumer.vertex(entry, ax, ay, az).color(color);
        consumer.vertex(entry, bx, by, bz).color(color);
        consumer.vertex(entry, cx, cy, cz).color(color);
        consumer.vertex(entry, dx, dy, dz).color(color);
    }

    private static void drawOutline(MatrixStack matrices, VertexConsumer consumer, Box box, Vec3d camera, int color, float width) {
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);
        line(consumer, entry, x1, y1, z1, x2, y1, z1, 1, 0, 0, color, width);
        line(consumer, entry, x1, y2, z1, x2, y2, z1, 1, 0, 0, color, width);
        line(consumer, entry, x1, y1, z2, x2, y1, z2, 1, 0, 0, color, width);
        line(consumer, entry, x1, y2, z2, x2, y2, z2, 1, 0, 0, color, width);
        line(consumer, entry, x1, y1, z1, x1, y2, z1, 0, 1, 0, color, width);
        line(consumer, entry, x2, y1, z1, x2, y2, z1, 0, 1, 0, color, width);
        line(consumer, entry, x1, y1, z2, x1, y2, z2, 0, 1, 0, color, width);
        line(consumer, entry, x2, y1, z2, x2, y2, z2, 0, 1, 0, color, width);
        line(consumer, entry, x1, y1, z1, x1, y1, z2, 0, 0, 1, color, width);
        line(consumer, entry, x2, y1, z1, x2, y1, z2, 0, 0, 1, color, width);
        line(consumer, entry, x1, y2, z1, x1, y2, z2, 0, 0, 1, color, width);
        line(consumer, entry, x2, y2, z1, x2, y2, z2, 0, 0, 1, color, width);
    }

    private static void line(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float ax, float ay, float az, float bx, float by, float bz,
        float nx, float ny, float nz, int color, float width
    ) {
        consumer.vertex(entry, ax, ay, az).color(color).normal(entry, nx, ny, nz).lineWidth(width);
        consumer.vertex(entry, bx, by, bz).color(color).normal(entry, nx, ny, nz).lineWidth(width);
    }

    record WorldBox(Box box, int fillColor, int lineColor, float lineWidth) {
    }

    record WorldLineStrip(List<Vec3d> points, int color, float width) {
    }

    private record Frame(List<WorldBox> boxes, List<WorldLineStrip> lineStrips,
                         List<WorldGlowRenderer.Quad> glows, PlayerEchoRenderer.Frame echoes,
                         PlayerEchoRenderer.Frame chams, List<WorldGlowRenderer.Quad> blockMarkers,
                         AmbientEffectsRenderer.Frame atmosphere, SkyLyricsRenderer.Frame lyrics) {
        private static final Frame EMPTY = new Frame(List.of(), List.of(), List.of(),
            PlayerEchoRenderer.Frame.EMPTY, PlayerEchoRenderer.Frame.EMPTY, List.of(), AmbientEffectsRenderer.Frame.EMPTY, SkyLyricsRenderer.Frame.EMPTY);
    }

    private static final List<SolidVisualMesh.Face> ANCHOR_PREVIEW_SHAPE = BlockHighlightMesh.surface(
        List.of(new BlockHighlightMesh.Cuboid(-.002, -.002, -.002, 1.002, 1.002, 1.002)), 6);
}
