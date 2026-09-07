package dev.c4lam.celeste.module.impl.visual;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.module.impl.visual.expansion.PlayerEchoRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.WorldGlowRenderer;
import dev.c4lam.celeste.module.impl.visual.expansion.UnifiedChamsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Fabric extraction/drawing bridge shared by Celeste world overlays. */
public final class VisualWorldRenderer {
    private static final RenderLayer FILLS = RenderLayer.of(
        "celeste_visual_fills",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_fills"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build()
        )).translucent().build()
    );
    private static final RenderLayer LINES = RenderLayer.of(
        "celeste_visual_lines",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_lines"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build()
        )).translucent().build()
    );
    private static final RenderStateDataKey<Frame> FRAME_KEY =
        RenderStateDataKey.create(() -> "celeste_visual_boxes");
    private static boolean initialized;

    private VisualWorldRenderer() {
    }

    public static synchronized void initialize(EntityEspFeature entities, HoleEspFeature holes) {
        if (initialized) {
            return;
        }
        initialized = true;
        UnifiedChamsRenderer.initialize();

        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:world_visuals/extract",
            WorldRenderEvents.EndExtraction.class, context -> {
            if (!AuthBootstrap.authorized() || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) {
                return;
            }
            var worldState = context.worldState();
            if (worldState == null) {
                return;
            }

            List<WorldBox> boxes = new ArrayList<>();
            List<WorldGlowRenderer.Quad> glows = new ArrayList<>();
            List<PlayerEchoRenderer.Echo> chams = new ArrayList<>();
            entities.extract(context, boxes, chams);
            holes.extract(context, boxes, glows);
            worldState.setData(FRAME_KEY, boxes.isEmpty() && glows.isEmpty() && chams.isEmpty() ? Frame.EMPTY
                : new Frame(List.copyOf(boxes), List.copyOf(glows), new PlayerEchoRenderer.Frame(chams)));
        }, WorldRenderEvents.END_EXTRACTION::register);
        // Fabric 16.2.x invokes BEFORE_DEBUG_RENDER from the outer extraction method,
        // before WorldRenderContextImpl is prepared for the frame-graph draw pass.
        BetaSubscriptions.register(WorldRenderEvents.AFTER_ENTITIES, "celeste:world_visuals/draw",
            WorldRenderEvents.AfterEntities.class, VisualWorldRenderer::draw, WorldRenderEvents.AFTER_ENTITIES::register);
    }

    static int argb(float alpha, int rgb) {
        int a = MathHelper.clamp(Math.round(alpha * 255.0F), 0, 255);
        return a << 24 | rgb & 0x00FFFFFF;
    }

    private static void draw(WorldRenderContext context) {
        if (!AuthBootstrap.authorized() || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) {
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
        if (frame == null) {
            return;
        }

        Vec3d camera = worldState.cameraRenderState.pos;
        if (camera == null) {
            return;
        }

        // Custom layers share a fallback builder. Finish every fill before asking
        // for the line consumer: switching layers ends the previous builder.
        VertexConsumer fills = consumers.getBuffer(FILLS);
        for (WorldBox box : frame.boxes()) {
            if ((box.fillColor() >>> 24) != 0 || (box.topFillColor() >>> 24) != 0) {
                drawFill(matrices, fills, box, camera);
            }
        }
        VertexConsumer lines = consumers.getBuffer(LINES);
        for (WorldBox box : frame.boxes()) {
            if (box.glow() && (box.lineColor() >>> 24) != 0) {
                drawOutline(matrices, lines, box, camera, scaleAlpha(box.lineColor(), 0.14F), box.lineWidth() + 3.5F);
            }
        }
        for (WorldBox box : frame.boxes()) {
            if ((box.lineColor() >>> 24) != 0) {
                drawOutline(matrices, lines, box, camera, box.lineColor(), box.lineWidth());
            }
        }
        // Each material owns a complete batch; no fallback consumer survives a layer switch.
        UnifiedChamsRenderer.draw(context, frame.chams());
        WorldGlowRenderer.draw(context, frame.glows());
    }

    private static void drawFill(MatrixStack matrices, VertexConsumer consumer, WorldBox visual, Vec3d camera) {
        Box box = visual.box();
        int color = visual.fillColor();
        int topColor = visual.topFillColor();
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);

        quad(consumer, entry, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, color);
        if (visual.shape() == Shape.FOOTPRINT) return;
        if ((topColor >>> 24) != 0) {
            quad(consumer, entry, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, topColor);
        }
        verticalQuad(consumer, entry, x1, z1, x2, z1, y1, y2, color, topColor);
        verticalQuad(consumer, entry, x2, z2, x1, z2, y1, y2, color, topColor);
        verticalQuad(consumer, entry, x1, z2, x1, z1, y1, y2, color, topColor);
        verticalQuad(consumer, entry, x2, z1, x2, z2, y1, y2, color, topColor);
    }

    private static void verticalQuad(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float x1, float z1, float x2, float z2, float y1, float y2, int bottom, int top
    ) {
        consumer.vertex(entry, x1, y1, z1).color(bottom);
        consumer.vertex(entry, x2, y1, z2).color(bottom);
        consumer.vertex(entry, x2, y2, z2).color(top);
        consumer.vertex(entry, x1, y2, z1).color(top);
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

    private static void drawOutline(
        MatrixStack matrices, VertexConsumer consumer, WorldBox visual, Vec3d camera, int color, float width
    ) {
        Box box = visual.box();
        MatrixStack.Entry entry = matrices.peek();
        float x1 = (float) (box.minX - camera.x);
        float y1 = (float) (box.minY - camera.y);
        float z1 = (float) (box.minZ - camera.z);
        float x2 = (float) (box.maxX - camera.x);
        float y2 = (float) (box.maxY - camera.y);
        float z2 = (float) (box.maxZ - camera.z);

        if (visual.shape() == Shape.CORNERS) {
            drawCorners(consumer, entry, x1, y1, z1, x2, y2, z2, color, width);
            return;
        }
        if (visual.shape() == Shape.FOOTPRINT || visual.shape() == Shape.BEACON) {
            line(consumer, entry, x1, y1, z1, x2, y1, z1, 1, 0, 0, color, width);
            line(consumer, entry, x2, y1, z1, x2, y1, z2, 0, 0, 1, color, width);
            line(consumer, entry, x1, y1, z2, x2, y1, z2, 1, 0, 0, color, width);
            line(consumer, entry, x1, y1, z1, x1, y1, z2, 0, 0, 1, color, width);
            return;
        }

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

    private static void drawCorners(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float x1, float y1, float z1, float x2, float y2, float z2, int color, float width
    ) {
        float dx = (x2 - x1) * 0.26F;
        float dy = Math.min((y2 - y1) * 0.18F, 0.26F);
        float dz = (z2 - z1) * 0.26F;
        for (int corner = 0; corner < 8; corner++) {
            boolean right = (corner & 1) != 0;
            boolean top = (corner & 2) != 0;
            boolean back = (corner & 4) != 0;
            float x = right ? x2 : x1;
            float y = top ? y2 : y1;
            float z = back ? z2 : z1;
            int sx = right ? -1 : 1;
            int sy = top ? -1 : 1;
            int sz = back ? -1 : 1;
            line(consumer, entry, x, y, z, x + dx * sx, y, z, sx, 0, 0, color, width);
            line(consumer, entry, x, y, z, x, y + dy * sy, z, 0, sy, 0, color, width);
            line(consumer, entry, x, y, z, x, y, z + dz * sz, 0, 0, sz, color, width);
        }
    }

    private static int scaleAlpha(int color, float factor) {
        return Math.round((color >>> 24) * factor) << 24 | color & 0xFFFFFF;
    }

    private static void line(
        VertexConsumer consumer, MatrixStack.Entry entry,
        float ax, float ay, float az, float bx, float by, float bz,
        float nx, float ny, float nz, int color, float width
    ) {
        consumer.vertex(entry, ax, ay, az).color(color).normal(entry, nx, ny, nz).lineWidth(width);
        consumer.vertex(entry, bx, by, bz).color(color).normal(entry, nx, ny, nz).lineWidth(width);
    }

    public enum Shape {
        BOX,
        CORNERS,
        FOOTPRINT,
        BEACON
    }

    public record WorldBox(
        Box box, int fillColor, int topFillColor, int lineColor, float lineWidth, Shape shape, boolean glow
    ) {
        public WorldBox(Box box, int fillColor, int lineColor, float lineWidth) {
            this(box, fillColor, fillColor, lineColor, lineWidth, Shape.BOX, false);
        }
    }

    private record Frame(List<WorldBox> boxes, List<WorldGlowRenderer.Quad> glows,
                         PlayerEchoRenderer.Frame chams) {
        private static final Frame EMPTY = new Frame(List.of(), List.of(), PlayerEchoRenderer.Frame.EMPTY);
    }
}
