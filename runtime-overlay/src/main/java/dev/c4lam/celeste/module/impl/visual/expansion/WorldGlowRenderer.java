package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Bounded textured batches; no entities, particles, packets or per-frame texture uploads. */
public final class WorldGlowRenderer {
    private static final int MAX_QUADS = 768;
    private static final RenderPipeline PIPELINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
            .withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/fake_lag_glow"))
            .withVertexShader(Identifier.ofVanilla("core/position_tex_color"))
            .withFragmentShader(Identifier.ofVanilla("core/position_tex_color"))
            .withSampler("Sampler0")
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            // SRC_ALPHA + ONE respects the PNG and vertex alpha; ADDITIVE ignores both.
            .withBlend(BlendFunction.LIGHTNING)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false)
            .withCull(false)
            .build()
    );
    private static final RenderLayer[] LAYERS = {
        layer("halo"), layer("soft"), layer("streak"),
        WorldVfxRenderer.ribbonLayer(), WorldVfxRenderer.pulseLayer(),
        WorldVfxRenderer.jumpLayer(), WorldVfxRenderer.splashLayer(), WorldVfxRenderer.dropletLayer(),
        WorldVfxRenderer.prismLayer(), WorldVfxRenderer.waterLayer()
    };

    public enum Texture { HALO, SOFT, STREAK, RIBBON, PULSE, JUMP, SPLASH, DROPLET, PRISM, WATER, VOLUME }

    /** World-space corners, in UV order (0,0), (1,0), (1,1), (0,1). */
    public record Quad(Texture texture, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int color, int endColor,
                       float uStart, float uEnd) {
        public Quad(Texture texture, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int color) {
            this(texture, a, b, c, d, color, color, 0F, 1F);
        }
        public Quad(Texture texture, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int color, int endColor) {
            this(texture, a, b, c, d, color, endColor, 0F, 1F);
        }
    }

    private WorldGlowRenderer() { }

    /** Register the cached pipeline with the other visual pipelines, before drawing. */
    public static void initialize() { UnifiedVolumeRenderer.initialize(); }

    private static RenderLayer layer(String texture) {
        return RenderLayer.of("celeste_fake_lag_glow_" + texture,
            RenderSetup.builder(PIPELINE)
                .texture("Sampler0", Identifier.of(CelesteClient.MOD_ID,
                    "textures/visual/fake_lag/" + texture + ".png"),
                    () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true))
                .translucent().build());
    }

    public static void draw(WorldRenderContext context, List<Quad> quads) {
        if (quads.isEmpty()) return;
        var state = context.worldState();
        if (state == null || state.cameraRenderState == null || context.matrices() == null
            || context.consumers() == null) return;
        Vec3d camera = state.cameraRenderState.pos;
        if (camera == null) return;
        MatrixStack.Entry entry = context.matrices().peek();
        int count = Math.min(MAX_QUADS, quads.size());
        RenderLayer lastLayer = null;
        // A layer switch ends the shared fallback BufferBuilder. Never retain a
        // consumer across switches or interleave the three texture batches.
        for (int texture = 0; texture < LAYERS.length; texture++) {
            VertexConsumer consumer = null;
            for (int index = 0; index < count; index++) {
                Quad quad = quads.get(index);
                if (quad.texture().ordinal() != texture || ((quad.color() | quad.endColor()) >>> 24) == 0) continue;
                if (consumer == null) {
                    lastLayer = LAYERS[texture];
                    consumer = context.consumers().getBuffer(lastLayer);
                }
                vertex(consumer, entry, quad.a(), camera, quad.uStart(), 0F, quad.color());
                vertex(consumer, entry, quad.b(), camera, quad.uEnd(), 0F, quad.endColor());
                vertex(consumer, entry, quad.c(), camera, quad.uEnd(), 1F, quad.endColor());
                vertex(consumer, entry, quad.d(), camera, quad.uStart(), 1F, quad.color());
            }
        }
        // Finalize only our last pending layer before the immediate private-depth pass.
        // Do not flush the whole provider or disturb unrelated vanilla layers.
        if (lastLayer != null && context.consumers() instanceof VertexConsumerProvider.Immediate immediate)
            immediate.draw(lastLayer);
        UnifiedVolumeRenderer.draw(context, quads);
    }

    private static void vertex(VertexConsumer consumer, MatrixStack.Entry matrix, Vec3d position,
                               Vec3d camera, float u, float v, int color) {
        consumer.vertex(matrix, (float) (position.x - camera.x), (float) (position.y - camera.y),
                (float) (position.z - camera.z)).texture(u, v).color(color);
    }
}
