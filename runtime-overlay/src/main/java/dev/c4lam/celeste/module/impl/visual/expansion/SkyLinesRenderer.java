package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.c4lam.celeste.CelesteClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** One additive, scene-depth-tested batch on the standard world renderer path, including Sodium. */
public final class SkyLinesRenderer {
    public record Frame(SkyLinesMath.Geometry geometry) {
        public static final Frame EMPTY = new Frame(SkyLinesMath.Geometry.EMPTY);
        public boolean isEmpty() { return geometry.isEmpty(); }
    }
    private static final RenderLayer LINES = RenderLayer.of("celeste_ambient_sky_lines",
        RenderSetup.builder(dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/ambient_sky_lines"))
                .withBlend(BlendFunction.LIGHTNING).withCull(false)
                .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(false).build()
        )).translucent().expectedBufferSize(65536).build());

    private SkyLinesRenderer() { }
    public static void initialize() { }

    public static void draw(WorldRenderContext context, Frame frame) {
        if (frame == null || frame.isEmpty() || context.matrices() == null || context.consumers() == null
            || context.worldState() == null || context.worldState().cameraRenderState == null
            || !context.worldState().cameraRenderState.initialized) return;
        MatrixStack.Entry matrix = context.matrices().peek();
        VertexConsumer consumer = context.consumers().getBuffer(LINES);
        SkyLinesMesh.emit(frame.geometry(), (x, y, z, color) ->
            consumer.vertex(matrix, (float)x, (float)y, (float)z).color(color));
        // Flush only this layer; never alter blend/depth globals or other renderers' buffers.
        if (context.consumers() instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(LINES);
    }

}
