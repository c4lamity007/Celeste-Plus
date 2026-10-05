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
public final class HoleEspWorldVfxRenderer {


    private static final java.util.Map<String,RenderLayer> MATERIALS = new java.util.HashMap<>();
    private HoleEspWorldVfxRenderer() { }

    public static RenderLayer ribbonLayer() { return cached("ribbon", false, "textures/visual/energy_ribbon.png"); }
    public static RenderLayer pulseLayer() { return cached("pulse", false, "textures/visual/fake_lag/halo.png"); }
    public static RenderLayer echoLayer() { return cached("echo", true, "textures/visual/energy_ribbon.png"); }
    public static RenderLayer jumpLayer() { return cached("jump", false, "", true); }
    public static RenderLayer jumpClassicLayer() { throw new IllegalStateException("Jump art is owned by Jump Circles"); }
    public static RenderLayer jumpLeanLayer() { return cached("jump_lean", false, "", true); }
    public static RenderLayer bloomLayer() { return cached("bloom", false, "", false); }
    public static RenderLayer splashLayer() { throw new IllegalStateException("Jump art is owned by Jump Circles"); }
    public static RenderLayer dropletLayer() { return cached("droplet", false, "textures/visual/fake_lag/soft.png", true); }
    public static RenderLayer prismLayer() { return cached("prism", false, "textures/visual/plasma_flow.png"); }
    public static RenderLayer waterLayer() { return cached("water", false, "textures/visual/plasma_flow.png", true); }
    public static RenderLayer trajectoryLayer() { return cached("trajectory", false, "textures/visual/fake_lag/soft.png"); }
    public static RenderLayer trajectoryImpactLayer() { return cached("trajectory_impact", false, "textures/visual/fake_lag/soft.png"); }

    private static RenderLayer cached(String name, boolean normals, String texture) {
        return cached(name,normals,texture,false);
    }
    private static RenderLayer cached(String name, boolean normals, String texture, boolean depthTest) {
        RenderSystem.assertOnRenderThread();
        return MATERIALS.computeIfAbsent(name,ignored->material(name,normals,texture,depthTest));
    }

    private static RenderLayer material(String name, boolean normals, String texture) {
        return material(name, normals, texture, false);
    }

    private static RenderLayer material(String name, boolean normals, String texture, boolean depthTest) {
        boolean analyticRing = "jump".equals(name) || "jump_lean".equals(name) || "bloom".equals(name);
        RenderPipeline.Builder builder = RenderPipeline.builder(
            analyticRing ? new RenderPipeline.Snippet[] {RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET}
                : new RenderPipeline.Snippet[] {RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET});
        builder.withLocation(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "pipeline/world_vfx_" + name)))
            .withVertexShader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID,
                "water".equals(name) ? "core/world_water" : "core/world_vfx")))
            .withFragmentShader(dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, "core/world_" + name)))
            .withShaderDefine("ECHO_MESH", normals ? 1 : 0)
            .withVertexFormat(normals ? VertexFormats.POSITION_TEXTURE_COLOR_NORMAL
                : VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
            .withBlend("water".equals(name) ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING)
            .withDepthTestFunction(depthTest ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
            .withDepthWrite(false).withCull(false);
        if (!analyticRing) builder.withSampler("Sampler0");
        RenderPipeline pipeline = dev.c4lam.celeste.compat.BetaRenderPipelines.register(builder.build());
        RenderSetup.Builder setup = RenderSetup.builder(pipeline);
        if (!analyticRing) setup.texture("Sampler0", dev.c4lam.celeste.module.delivery.ModuleResourceNames.asset(Identifier.of(CelesteClient.MOD_ID, texture)),
            () -> RenderSystem.getSamplerCache().get(FilterMode.LINEAR, true));
        return RenderLayer.of("celeste_world_vfx_" + name,
            setup.translucent().expectedBufferSize(normals ? 262144 : 65536).build());
    }
}
