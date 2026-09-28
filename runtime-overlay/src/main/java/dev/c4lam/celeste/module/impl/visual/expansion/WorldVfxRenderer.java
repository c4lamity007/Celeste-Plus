package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.CelesteClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Cached, frame-animated GPU materials shared by world VFX. */
public final class WorldVfxRenderer {
    private static final RenderLayer RIBBON = material("ribbon", false, "textures/visual/energy_ribbon.png");
    private static final RenderLayer PULSE = material("pulse", false, "textures/visual/fake_lag/halo.png");
    private static final RenderLayer ECHO = material("echo", true, "textures/visual/energy_ribbon.png");
    private static final RenderLayer JUMP = material("jump", false, "textures/visual/jump/glow.png", true);
    private static final RenderLayer JUMP_LEAN = material("jump_lean", false, "textures/visual/jump/glow.png", true);
    private static final RenderLayer SPLASH = material("splash", false, "textures/visual/jump/splash.png", true);
    private static final RenderLayer DROPLET = material("droplet", false, "textures/visual/fake_lag/soft.png", true);
    private static final RenderLayer PRISM = material("prism", false, "textures/visual/plasma_flow.png");
    private static final RenderLayer WATER = material("water", false, "textures/visual/plasma_flow.png", true);
    private static final RenderLayer TRAJECTORY = material("trajectory", false, "textures/visual/fake_lag/soft.png");
    private static final RenderLayer TRAJECTORY_IMPACT = material("trajectory_impact", false, "textures/visual/fake_lag/soft.png");

    private WorldVfxRenderer() { }

    public static RenderLayer ribbonLayer() { return RIBBON; }
    public static RenderLayer pulseLayer() { return PULSE; }
    public static RenderLayer echoLayer() { return ECHO; }
    public static RenderLayer jumpLayer() { return JUMP; }
    public static RenderLayer jumpLeanLayer() { return JUMP_LEAN; }
    public static RenderLayer splashLayer() { return SPLASH; }
    public static RenderLayer dropletLayer() { return DROPLET; }
    public static RenderLayer prismLayer() { return PRISM; }
    public static RenderLayer waterLayer() { return WATER; }
    public static RenderLayer trajectoryLayer() { return TRAJECTORY; }
    public static RenderLayer trajectoryImpactLayer() { return TRAJECTORY_IMPACT; }

    private static RenderLayer material(String name, boolean normals, String texture) {
        return material(name, normals, texture, false);
    }

    private static RenderLayer material(String name, boolean normals, String texture, boolean depthTest) {
        boolean analyticRing = "jump".equals(name) || "jump_lean".equals(name);
        RenderPipeline.Builder builder = RenderPipeline.builder(
            analyticRing ? new RenderPipeline.Snippet[] {RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET}
                : new RenderPipeline.Snippet[] {RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET});
        builder.withLocation(Identifier.of(CelesteClient.MOD_ID, "pipeline/world_vfx_" + name))
            .withVertexShader(Identifier.of(CelesteClient.MOD_ID,
                "water".equals(name) ? "core/world_water" : "core/world_vfx"))
            .withFragmentShader(Identifier.of(CelesteClient.MOD_ID, "core/world_" + name))
            .withShaderDefine("ECHO_MESH", normals ? 1 : 0)
            .withVertexFormat(normals ? VertexFormats.POSITION_TEXTURE_COLOR_NORMAL
                : VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend("water".equals(name) ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING)
            .withDepthTestFunction(depthTest ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withCull(false);
        if (!analyticRing) builder.withSampler("Sampler0");
        RenderPipeline pipeline = dev.c4lam.celeste.compat.BetaRenderPipelines.register(builder.build());
        RenderSetup.Builder setup = RenderSetup.builder(pipeline);
        if (!analyticRing) setup.texture("Sampler0", Identifier.of(CelesteClient.MOD_ID, texture),
            () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true));
        return RenderLayer.of("celeste_world_vfx_" + name,
            setup.translucent().expectedBufferSize(normals ? 262144 : 65536).build());
    }
}
