package dev.c4lam.celeste.render.model;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Indexed custom geometry with scene depth/fog and reusable CPU skinning buffers. */
public final class PlayerMeshRenderer implements AutoCloseable {
    private static final RenderPipeline SOLID = pipeline(false, false), SOLID_CULL = pipeline(false, true);
    private static final RenderPipeline BLEND = pipeline(true, false), BLEND_CULL = pipeline(true, true);
    private static final int STRIDE = VertexFormats.POSITION_TEXTURE_COLOR_NORMAL.getVertexSize();
    private final GlbModel model;
    private final GlbPose pose;
    private final ModelSecondaryMotion secondaryMotion;
    private final List<Part> parts = new ArrayList<>();
    private final List<NativeImageBackedTexture> textures = new ArrayList<>();
    private final List<GpuBuffer> materials = new ArrayList<>();
    private final int vertexCount, indexCount;
    private final float centerX, baseY, centerZ, fit;
    private BufferAllocator allocator;
    private boolean uploaded, closed;

    public PlayerMeshRenderer(GlbModel model) {
        this.model = model;
        pose = new GlbPose(model);
        int vertices = 0, indices = 0;
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int node : model.sceneNodes()) {
            var meshNode = model.nodes().get(node);
            if (meshNode.mesh() < 0) continue;
            for (var primitive : model.meshes().get(meshNode.mesh()).primitives()) {
                if (primitive.indices().length == 0) continue;
                vertices = Math.addExact(vertices, primitive.vertexCount());
                indices = Math.addExact(indices, primitive.indices().length);
                if (vertices > 200_000 || indices > 1_200_000 || parts.size() >= 128)
                    throw new IllegalArgumentException("Use at most 200,000 vertices and 128 mesh parts");
                Part part = new Part(node, primitive);
                pose.deform(node, primitive, part.positions, part.normals);
                for (int index : primitive.indices()) {
                    int p = index * 3;
                    minX = Math.min(minX, part.positions[p]); maxX = Math.max(maxX, part.positions[p]);
                    minY = Math.min(minY, part.positions[p + 1]); maxY = Math.max(maxY, part.positions[p + 1]);
                    minZ = Math.min(minZ, part.positions[p + 2]); maxZ = Math.max(maxZ, part.positions[p + 2]);
                }
                parts.add(part);
            }
        }
        if (parts.isEmpty() || !Float.isFinite(maxY - minY) || maxY - minY < .00001f)
            throw new IllegalArgumentException("Selected scene has no model with a usable height");
        vertexCount = vertices; indexCount = indices;
        centerX = minX * .5f + maxX * .5f; baseY = minY; centerZ = minZ * .5f + maxZ * .5f;
        fit = 1.8f / (maxY - minY);
        if (!Float.isFinite(fit) || (maxX - minX) * fit > 16 || (maxZ - minZ) * fit > 16)
            throw new IllegalArgumentException("Model proportions are too wide for a player");
        secondaryMotion = new ModelSecondaryMotion(model, centerX, baseY, centerZ);
        parts.sort(Comparator.comparingInt(part -> material(part).alphaMode() == GlbModel.AlphaMode.BLEND ? 1 : 0));
    }

    public int vertexCount() { return vertexCount; }
    public int indexCount() { return indexCount; }
    public int secondaryJointCount() { return secondaryMotion.jointCount(); }

    public boolean draw(WorldRenderContext context, List<Instance> instances) {
        if (closed || instances.isEmpty() || context.matrices() == null || context.worldState() == null
            || context.worldState().cameraRenderState == null) return false;
        RenderSystem.assertOnRenderThread();
        var framebuffer = MinecraftClient.getInstance().getFramebuffer();
        var color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride : framebuffer.getColorAttachmentView();
        var depth = RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : framebuffer.getDepthAttachmentView();
        Vec3d camera = context.worldState().cameraRenderState.pos;
        if (color == null || depth == null || color.isClosed() || depth.isClosed() || camera == null) return false;
        upload();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        List<Instance> ordered = instances.stream().sorted(Comparator.comparingDouble(
            (Instance instance) -> instance.position.squaredDistanceTo(camera)).reversed()).toList();
        for (Instance instance : ordered) {
            pose.sample(instance.animation.clip(), instance.animation.seconds(), instance.animation.loop());
            secondaryMotion.apply(pose, new ModelSecondaryMotion.Frame(instance.playerId, instance.motionTime,
                instance.position.x, instance.position.y, instance.position.z, instance.yaw,
                fit * instance.scale, instance.secondaryMotion, instance.motionStrength));
            Matrix4f facing = new Matrix4f().rotateY((float) Math.toRadians(-instance.yaw));
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .mul(context.matrices().peek().getPositionMatrix())
                .translate((float) (instance.position.x - camera.x), (float) (instance.position.y - camera.y), (float) (instance.position.z - camera.z))
                .mul(facing).scale(fit * instance.scale).translate(-centerX, -baseY, -centerZ);
            var transforms = RenderSystem.getDynamicUniforms().write(modelView,
                new Vector4f(instance.light, instance.light, instance.light, 1), new Vector3f(), facing);
            for (Part part : parts) {
                pose.deform(part.node, part.primitive, part.positions, part.normals);
                var material = material(part);
                float[] factor = material.baseColorFactor();
                int tint = channel(factor[3]) << 24 | channel(factor[0]) << 16 | channel(factor[1]) << 8 | channel(factor[2]);
                BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
                try {
                    for (int vertex = 0; vertex < part.primitive.vertexCount(); vertex++) {
                        int p = vertex * 3, uv = vertex * 2;
                        builder.vertex(part.positions[p], part.positions[p + 1], part.positions[p + 2])
                            .texture(part.primitive.texCoords() == null ? 0 : part.primitive.texCoords()[uv],
                                part.primitive.texCoords() == null ? 0 : part.primitive.texCoords()[uv + 1])
                            .color(tint).normal(part.normals[p], part.normals[p + 1], part.normals[p + 2]);
                    }
                    try (BuiltBuffer built = builder.end()) { encoder.writeToBuffer(part.vertices.slice(), built.getBuffer()); }
                } finally { allocator.clear(); }
                boolean blend = material.alphaMode() == GlbModel.AlphaMode.BLEND;
                int orientation = orientation(part.node);
                boolean cull = !material.doubleSided() && orientation != 0;
                RenderPipeline pipeline = blend ? (cull ? BLEND_CULL : BLEND) : (cull ? SOLID_CULL : SOLID);
                int image = material.image() < 0 ? model.images().size() : material.image();
                var filter = material.linearFiltering() ? FilterMode.LINEAR : FilterMode.NEAREST;
                var sampler = RenderSystem.getSamplerCache().get(address(material.wrapS()), address(material.wrapT()), filter, filter, false);
                try (var pass = encoder.createRenderPass(() -> "Celeste imported player", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
                    pass.setPipeline(pipeline);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    pass.setUniform("ModelMaterial", materials.get(part.primitive.material()));
                    pass.bindTexture("Sampler0", textures.get(image).getGlTextureView(), sampler);
                    pass.setVertexBuffer(0, part.vertices);
                    pass.setIndexBuffer(orientation < 0 ? part.reversedIndices : part.indices, VertexFormat.IndexType.INT);
                    pass.drawIndexed(0, 0, part.primitive.indices().length, 1);
                }
            }
        }
        return true;
    }

    private void upload() {
        if (uploaded) return;
        try {
            var device = RenderSystem.getDevice();
            for (var pipeline : List.of(SOLID, SOLID_CULL, BLEND, BLEND_CULL))
                if (!device.precompilePipeline(pipeline).isValid()) throw new IllegalStateException("Player model shader did not compile");
            allocator = new BufferAllocator(parts.stream().mapToInt(part -> part.primitive.vertexCount()).max().orElseThrow() * STRIDE);
            for (var image : model.images()) textures.add(texture(image.width(), image.height(), image.rgba()));
            textures.add(texture(1, 1, new byte[] {-1, -1, -1, -1}));
            for (var material : model.materials()) {
                ByteBuffer buffer = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
                try {
                    buffer.putFloat(material.alphaCutoff()).putFloat(material.alphaMode().ordinal())
                        .putFloat(material.wrapS() == 33648 ? 1 : 0).putFloat(material.wrapT() == 33648 ? 1 : 0).flip();
                    materials.add(device.createBuffer(() -> "Celeste model material", GpuBuffer.USAGE_UNIFORM, buffer));
                } finally { MemoryUtil.memFree(buffer); }
            }
            for (Part part : parts) {
                part.vertices = device.createBuffer(() -> "Celeste model vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    (long) part.primitive.vertexCount() * STRIDE);
                ByteBuffer indexBytes = MemoryUtil.memAlloc(part.primitive.indices().length * 4).order(ByteOrder.nativeOrder());
                try {
                    for (int index : part.primitive.indices()) indexBytes.putInt(index);
                    indexBytes.flip();
                    part.indices = device.createBuffer(() -> "Celeste model indices", GpuBuffer.USAGE_INDEX, indexBytes);
                    indexBytes.clear();
                    int[] indices = part.primitive.indices();
                    for (int i = 0; i < indices.length; i += 3)
                        indexBytes.putInt(indices[i]).putInt(indices[i + 2]).putInt(indices[i + 1]);
                    indexBytes.flip();
                    part.reversedIndices = device.createBuffer(() -> "Celeste mirrored model indices", GpuBuffer.USAGE_INDEX, indexBytes);
                } finally { MemoryUtil.memFree(indexBytes); }
            }
            uploaded = true;
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private static NativeImageBackedTexture texture(int width, int height, byte[] rgba) {
        NativeImage image = new NativeImage(width, height, false);
        NativeImageBackedTexture texture = null;
        try {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                image.setColorArgb(x, y, (rgba[offset + 3] & 255) << 24 | (rgba[offset] & 255) << 16
                    | (rgba[offset + 1] & 255) << 8 | rgba[offset + 2] & 255);
            }
            texture = new NativeImageBackedTexture(() -> "Celeste imported model texture", image);
            texture.upload();
            return texture;
        } catch (RuntimeException | Error failure) {
            if (texture != null) texture.close(); else image.close();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        secondaryMotion.clear(); uploaded = false;
        for (Part part : parts) {
            if (part.vertices != null) part.vertices.close();
            if (part.indices != null) part.indices.close();
            if (part.reversedIndices != null) part.reversedIndices.close();
        }
        for (GpuBuffer buffer : materials) buffer.close();
        for (var texture : textures) texture.close();
        materials.clear(); textures.clear();
        if (allocator != null) allocator.close();
        allocator = null;
    }

    private GlbModel.Material material(Part part) { return model.materials().get(part.primitive.material()); }
    private int orientation(int node) {
        // Joint blends can reverse handedness even when each joint matrix does not.
        // Keep skinned exteriors visible through those poses; rigid meshes retain culling.
        return pose.skinMatrices(node) == null ? determinantSign(pose.nodeMatrix(node), 0) : 0;
    }
    private static int determinantSign(float[] m, int o) {
        double determinant = (double) m[o] * ((double) m[o + 5] * m[o + 10] - (double) m[o + 9] * m[o + 6])
            - (double) m[o + 4] * ((double) m[o + 1] * m[o + 10] - (double) m[o + 9] * m[o + 2])
            + (double) m[o + 8] * ((double) m[o + 1] * m[o + 6] - (double) m[o + 5] * m[o + 2]);
        return determinant < 0 ? -1 : determinant > 0 ? 1 : 0;
    }
    private static int channel(float value) { return Math.round(Math.max(0, Math.min(1, value)) * 255); }
    private static AddressMode address(int wrap) { return wrap == 10497 ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE; }
    private static RenderPipeline pipeline(boolean blend, boolean cull) {
        var builder = RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.FOG_SNIPPET)
            .withLocation(Identifier.of("celeste", "pipeline/player_model_" + (blend ? "blend" : "solid") + (cull ? "_cull" : "")))
            .withVertexShader(Identifier.of("celeste", "core/player_model"))
            .withFragmentShader(Identifier.of("celeste", "core/player_model"))
            .withSampler("Sampler0").withUniform("ModelMaterial", UniformType.UNIFORM_BUFFER)
            .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL, VertexFormat.DrawMode.TRIANGLES)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST).withDepthWrite(!blend).withColorWrite(true).withCull(cull);
        if (blend) builder.withBlend(BlendFunction.TRANSLUCENT); else builder.withoutBlend();
        return dev.c4lam.celeste.compat.BetaRenderPipelines.register(builder.build());
    }

    private static final class Part {
        final int node;
        final GlbModel.Primitive primitive;
        final float[] positions, normals;
        GpuBuffer vertices, indices, reversedIndices;
        Part(int node, GlbModel.Primitive primitive) {
            this.node = node; this.primitive = primitive;
            positions = new float[primitive.positions().length]; normals = new float[positions.length];
        }
    }
    public record Instance(Vec3d position, float yaw, float scale, float light, ModelAnimations.Playback animation,
                           java.util.UUID playerId, double motionTime, boolean secondaryMotion, float motionStrength) {
        public Instance(Vec3d position, float yaw, float scale, float light, ModelAnimations.Playback animation) {
            this(position, yaw, scale, light, animation, null, 0, false, 0);
        }
    }
}
