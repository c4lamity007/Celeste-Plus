package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalInt;

/** Three shader-generated vertices, no textures, persistent buffers, or depth writes. */
public final class SkyboxRenderer {
    private static final Logger LOGGER = dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/CustomSky");
    private static final RenderPipeline PIPELINE = dev.c4lam.celeste.compat.BetaRenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.FOG_SNIPPET)
            .withLocation(Identifier.of("celeste", "pipeline/skybox"))
            .withVertexShader(Identifier.of("celeste", "core/skybox"))
            .withFragmentShader(Identifier.of("celeste", "core/skybox"))
            .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
            .withoutBlend().withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withColorWrite(true).build());
    private static boolean warned;

    private SkyboxRenderer() { }

    public record Frame(long generation, int mode, float brightness, float stars, float phase, float rain) {
        public static final Frame EMPTY = new Frame(-1, 0, 0, 0, 0, 0);
    }

    /** Force pipeline registration during visual initialization, before first sky draw. */
    public static void initialize() { }

    /** Called only at vanilla renderTopSky, before terrain; true means replacement succeeded. */
    public static boolean draw(Frame frame) {
        if (!SkyboxFeature.isCurrent(frame) || RenderSystem.getShaderFog() == null
            || RenderSystem.getProjectionMatrixBuffer() == null) return false;
        RenderSystem.assertOnRenderThread();
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        if (main == null) return false;
        GpuTextureView target = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride : main.getColorAttachmentView();
        if (target == null || target.isClosed() || target.texture().isClosed()
            || target.getWidth(0) < 1 || target.getHeight(0) < 1 || !RenderSystem.getModelViewMatrix().isFinite()) return false;
        try {
            // Resource packs/reloads may temporarily omit or invalidate this shader.
            // The device caches this lookup; do not cancel vanilla for an invalid pipeline.
            if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) return false;
            var transforms = RenderSystem.getDynamicUniforms().write(RenderSystem.getModelViewMatrix(),
                new Vector4f(frame.mode(), frame.brightness(), frame.phase(), frame.stars()),
                new Vector3f(frame.rain(), 0, 0), new Matrix4f());
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Celeste procedural sky", target, OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.draw(0, 3);
            }
            warned = false;
            return true;
        } catch (RuntimeException failure) {
            if (!warned) {
                LOGGER.warn("Custom Sky unavailable; preserving vanilla sky", failure);
                warned = true;
            }
            return false;
        }
    }
}
