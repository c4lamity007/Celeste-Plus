package dev.c4lam.celeste.render;

import dev.c4lam.celeste.render.geometry.WorldBox;
import dev.c4lam.celeste.render.geometry.WorldBox.Shape;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.render.WorldOverlayPass;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

import dev.c4lam.celeste.module.delivery.ModuleServices;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import java.util.concurrent.atomic.AtomicReference;

/** Generic primitive drawing service; no module detection or behavior lives here. */
public final class ModuleBoxRenderer {
    private static final RenderLayer FILLS = RenderLayer.of(
        "celeste_module_boxes_fills",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/module_boxes_fills"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build()
        )).translucent().build()
    );
    private static final RenderLayer LINES = RenderLayer.of(
        "celeste_module_boxes_lines",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/module_boxes_lines"))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build()
        )).translucent().build()
    );
    @FunctionalInterface public interface Extractor {
        void extract(WorldExtractionContext context, List<WorldBox> output);
    }
    private ModuleBoxRenderer() { }
    /** Initialize shared host pipelines before entering any child module scope. */
    public static void initialize() { }

    public static void attach(ModuleServices services, Extractor extractor) {
        AtomicReference<List<WorldBox>> frame = new AtomicReference<>(List.of());
        services.scope().own(() -> frame.set(List.of()));
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION, "celeste:module/" + services.moduleId() + "/boxes_extract",
            WorldRenderEvents.EndExtraction.class, context -> {
                if (dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
                ArrayList<WorldBox> output = new ArrayList<>();
                extractor.extract(context, output);
                frame.set(List.copyOf(output));
            }, WorldRenderEvents.END_EXTRACTION::register);
        WorldOverlayPass.register("celeste:module/" + services.moduleId() + "/boxes_draw", WorldOverlayPass.Stage.FILLS,
            context -> draw(context, frame.get()));
    }

    public static void draw(WorldRenderContext context, List<WorldBox> boxes) {
        if (boxes.isEmpty() || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
        var state = context.worldState();
        MatrixStack matrices = context.matrices();
        var consumers = context.consumers();
        if (state == null || state.cameraRenderState == null || !state.cameraRenderState.initialized
            || matrices == null || consumers == null) return;
        Vec3d camera = state.cameraRenderState.pos;
        if (camera == null) return;
        VertexConsumer fills = consumers.getBuffer(FILLS);
        for (WorldBox box : boxes) if ((box.fillColor() >>> 24) != 0 || (box.topFillColor() >>> 24) != 0)
            drawFill(matrices, fills, box, camera);
        VertexConsumer lines = consumers.getBuffer(LINES);
        for (WorldBox box : boxes) if (box.glow() && (box.lineColor() >>> 24) != 0)
            drawOutline(matrices, lines, box, camera, scaleAlpha(box.lineColor(), 0.14F), box.lineWidth() + 3.5F);
        for (WorldBox box : boxes) if ((box.lineColor() >>> 24) != 0)
            drawOutline(matrices, lines, box, camera, box.lineColor(), box.lineWidth());
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(FILLS);
            immediate.draw(LINES);
        }
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

}
