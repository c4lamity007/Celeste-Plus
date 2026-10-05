package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.delivery.ModuleFrame;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.render.geometry.WorldLineStrip;
import dev.c4lam.celeste.render.geometry.WorldBox;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.render.WorldOverlayPass;
import net.fabricmc.fabric.api.event.Event;
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

/** Child-owned primitive renderer, cloned privately with each visual module's implementation. */
public final class TrailsModuleVisualRenderer {
    private static final RenderLayer XRAY_QUADS = RenderLayer.of(
        "celeste_visual_expansion_quads",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_quads")))
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
                .withLocation(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "pipeline/visual_expansion_lines")))
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .build()
        )).translucent().build()
    );

    @FunctionalInterface public interface Extractor {
        void extract(net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext context,
                     List<WorldBox> boxes, List<WorldLineStrip> lines, List<TrailsWorldGlowRenderer.Quad> glows);
    }
    private record Frame(List<WorldBox> boxes,List<WorldLineStrip> lines,List<TrailsWorldGlowRenderer.Quad> glows) { }
    private TrailsModuleVisualRenderer() { }
    public static void attach(ModuleServices services, Extractor extractor) {
        TrailsWorldGlowRenderer.initialize();
        services.scope().releaseAfterCallbacks(TrailsUnifiedVolumeRenderer::releaseResources);
        ModuleFrame<Frame> frame = ModuleFrame.create();
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION,"celeste:module/"+services.moduleId()+"/extract",
            WorldRenderEvents.EndExtraction.class, context -> {
                if (context.worldState()==null || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
                List<WorldBox> boxes=new ArrayList<>(); List<WorldLineStrip> lines=new ArrayList<>();
                List<TrailsWorldGlowRenderer.Quad> glows=new ArrayList<>();
                extractor.extract(context,boxes,lines,glows);
                dev.c4lam.celeste.render.ModuleVisualBudget.commit(services.moduleId(),glows.size());
                frame.set(context.worldState(),new Frame(List.copyOf(boxes),List.copyOf(lines),List.copyOf(glows)));
            },proxy->WorldRenderEvents.END_EXTRACTION.register(dev.c4lam.celeste.render.ModuleVisualBudget.phase(services.moduleId()),proxy));
        WorldOverlayPass.register("celeste:module/"+services.moduleId()+"/fills",WorldOverlayPass.Stage.FILLS,
            context->draw(context,frame.get(context.worldState()),WorldOverlayPass.Stage.FILLS));
        WorldOverlayPass.register("celeste:module/"+services.moduleId()+"/lines",WorldOverlayPass.Stage.LINES,
            context->draw(context,frame.get(context.worldState()),WorldOverlayPass.Stage.LINES));
        WorldOverlayPass.register("celeste:module/"+services.moduleId()+"/glows",WorldOverlayPass.Stage.GLOWS,
            context->draw(context,frame.get(context.worldState()),WorldOverlayPass.Stage.GLOWS));
    }
    private static void draw(WorldRenderContext context,Frame frame,WorldOverlayPass.Stage stage) {
        if (frame==null || context.worldState()==null || context.worldState().cameraRenderState==null
            || context.matrices()==null || context.consumers()==null
            || dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
        Vec3d camera=context.worldState().cameraRenderState.pos;
        if(camera==null) return;
        if(stage==WorldOverlayPass.Stage.FILLS) {
            if(frame.boxes.isEmpty())return;
            VertexConsumer fills=context.consumers().getBuffer(XRAY_QUADS);
            for(WorldBox box:frame.boxes) if((box.fillColor()>>>24)!=0) drawFill(context.matrices(),fills,box.box(),camera,box.fillColor());
            if(context.consumers() instanceof VertexConsumerProvider.Immediate immediate)immediate.draw(XRAY_QUADS);
        } else if(stage==WorldOverlayPass.Stage.LINES) {
            if(frame.boxes.isEmpty()&&frame.lines.isEmpty())return;
            VertexConsumer lines=context.consumers().getBuffer(XRAY_LINES);
            for(WorldBox box:frame.boxes) if((box.lineColor()>>>24)!=0) drawOutline(context.matrices(),lines,box.box(),camera,box.lineColor(),box.lineWidth());
            for(WorldLineStrip strip:frame.lines) drawLineStrip(context.matrices(),lines,strip,camera);
            if(context.consumers() instanceof VertexConsumerProvider.Immediate immediate)immediate.draw(XRAY_LINES);
        } else TrailsWorldGlowRenderer.draw(context,frame.glows);
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

}
