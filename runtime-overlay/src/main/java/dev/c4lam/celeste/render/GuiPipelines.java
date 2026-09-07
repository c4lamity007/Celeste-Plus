package dev.c4lam.celeste.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Celeste's render pipelines. Class loading registers every pipeline once. */
public final class GuiPipelines {
    public static final String UBO = "CelesteParams";

    public static final RenderPipeline RECT = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("rect", "rect/rect", VertexFormats.POSITION_COLOR).build()
    );
    public static final RenderPipeline GRADIENT_RECT = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("gradient_rect", "rect/gradient_rect", VertexFormats.POSITION_COLOR).build()
    );
    public static final RenderPipeline SELECTION_OUTLINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("selection_outline", "rect/selection_outline", VertexFormats.POSITION_COLOR).build()
    );
    public static final RenderPipeline TEXTURE_RECT = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("texture_rect", "rect/texture_rect", VertexFormats.POSITION_TEXTURE_COLOR)
                    .withSampler("Sampler0")
                    .build()
    );
    public static final RenderPipeline BLURRED_RECT = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("blurred_rect", "rect/blurred_rect", VertexFormats.POSITION_TEXTURE_COLOR)
                    .withSampler("Sampler0")
                    .build()
    );
    public static final RenderPipeline TEXT = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
            gui("text", "text", VertexFormats.POSITION_TEXTURE_COLOR)
                    .withSampler("Sampler0")
                    .build()
    );

    public static final RenderPipeline BLUR_DOWNSCALE = fullscreen("blur_downscale", "post/blur/downscale");
    public static final RenderPipeline BLUR_UPSCALE = fullscreen("blur_upscale", "post/blur/upscale");

    private GuiPipelines() {
    }

    /** Forces static registration during client initialization. */
    public static void register() {
    }

    private static RenderPipeline.Builder gui(String name, String shader, VertexFormat format) {
        return RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
                .withLocation(id("pipeline/" + name))
                .withVertexShader(shader(shader))
                .withFragmentShader(shader(shader))
                .withUniform(UBO, UniformType.UNIFORM_BUFFER)
                .withBlend(BlendFunction.TRANSLUCENT)
                .withCull(false)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withVertexFormat(format, VertexFormat.DrawMode.QUADS);
    }

    private static RenderPipeline fullscreen(String name, String shader) {
        return dev.c4lam.celeste.compat.BetaRenderPipelines.register(RenderPipeline.builder(RenderPipelines.POST_EFFECT_PROCESSOR_SNIPPET)
                .withLocation(id("pipeline/" + name))
                .withVertexShader(Identifier.ofVanilla("core/screenquad"))
                .withFragmentShader(shader(shader))
                .withSampler("InSampler")
                .withUniform(UBO, UniformType.UNIFORM_BUFFER)
                .build());
    }

    private static Identifier shader(String path) {
        return Identifier.of("celeste", "core/" + path);
    }

    private static Identifier id(String path) {
        return Identifier.of("celeste", path);
    }
}
