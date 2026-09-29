package dev.c4lam.celeste.module.impl.visual.expansion;

import com.mojang.blaze3d.vertex.VertexFormat;
import dev.c4lam.celeste.compat.ChamsEquipmentAccess;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.MovingBlockRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * Consumes vanilla equipment submissions immediately into bounded CPU geometry.
 * Nothing reaches vanilla's queue, GPU, world state or an entity's inventory.
 * The completed snapshot retains positions, UVs and texture IDs only.
 */
final class EquipmentCaptureQueue implements OrderedRenderCommandQueue {
    private final PlayerModelSnapshot.CaptureBuffer buffer;

    EquipmentCaptureQueue(PlayerModelSnapshot.CaptureBuffer buffer) { this.buffer = buffer; }

    @Override public RenderCommandQueue getBatchingQueue(int order) { return this; }

    private VertexConsumer consumer(RenderLayer layer, Sprite sprite) {
        // Glint and trim passes reuse existing geometry. In particular, glint
        // alpha must not fill transparent corners of the base item texture.
        String pipeline = layer.getRenderPipeline().getLocation().getPath();
        if (pipeline.contains("glint") || pipeline.contains("armor_decal")
            || layer.getDrawMode() != VertexFormat.DrawMode.QUADS) return null;
        Identifier texture = sprite == null ? ChamsEquipmentAccess.texture(layer) : sprite.getAtlasId();
        if (texture == null) return null;
        buffer.textureRange(texture);
        return sprite == null ? buffer : sprite.getTextureSpecificVertexConsumer(buffer);
    }

    @Override public <S> void submitModel(Model<? super S> model, S state, MatrixStack matrices,
            RenderLayer layer, int light, int overlay, int color, Sprite sprite,
            int outlineColor, ModelCommandRenderer.CrumblingOverlayCommand crumbling) {
        VertexConsumer out = consumer(layer, sprite);
        if (out == null) return;
        // Same pose/render order as ModelCommandRenderer, on our owned model.
        model.setAngles(state);
        model.render(matrices, out, light, overlay, color);
    }

    @Override public void submitModelPart(ModelPart part, MatrixStack matrices, RenderLayer layer,
            int light, int overlay, Sprite sprite, boolean sheeted, boolean glint,
            int color, ModelCommandRenderer.CrumblingOverlayCommand crumbling, int outlineColor) {
        VertexConsumer out = consumer(layer, sprite);
        if (out != null) part.render(matrices, out, light, overlay, color);
    }

    @Override public void submitItem(MatrixStack matrices, ItemDisplayContext displayContext,
            int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads,
            RenderLayer layer, ItemRenderState.Glint glint) {
        VertexConsumer out = consumer(layer, null);
        if (out == null) return;
        // The ItemRenderState already applied its real left/right-hand model
        // transform. Reuse vanilla's baked-quad emitter and suppress duplicate glint.
        ItemRenderer.renderItem(displayContext, matrices, ignored -> out, light, overlay,
            tints, quads, layer, ItemRenderState.Glint.NONE);
    }

    @Override public void submitCustom(MatrixStack matrices, RenderLayer layer, OrderedRenderCommandQueue.Custom custom) {
        VertexConsumer out = consumer(layer, null);
        if (out != null) custom.render(matrices.peek(), out);
    }

    @Override public void submitBlockStateModel(MatrixStack matrices, RenderLayer layer, BlockStateModel model,
            float red, float green, float blue, int light, int overlay, int outlineColor) {
        VertexConsumer out = consumer(layer, null);
        if (out != null) BlockModelRenderer.render(matrices.peek(), out, model, red, green, blue, light, overlay);
    }

    @Override public void submitBlock(MatrixStack matrices, BlockState state, int light, int overlay, int outlineColor) {
        MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(state, matrices,
            layer -> {
                VertexConsumer out = consumer(layer, null);
                return out == null ? DISCARD : out;
            }, light, overlay);
    }

    // These commands are not equipment geometry and are never included in chams.
    @Override public void submitShadowPieces(MatrixStack matrices, float opacity, List<EntityRenderState.ShadowPiece> pieces) { }
    @Override public void submitLabel(MatrixStack matrices, Vec3d offset, int y, Text text, boolean seeThrough,
            int light, double distanceSquared, CameraRenderState camera) { }
    @Override public void submitText(MatrixStack matrices, float x, float y, OrderedText text, boolean shadow,
            TextRenderer.TextLayerType layer, int color, int backgroundColor, int light, int outlineColor) { }
    @Override public void submitFire(MatrixStack matrices, EntityRenderState state, Quaternionf rotation) { }
    @Override public void submitLeash(MatrixStack matrices, EntityRenderState.LeashData leash) { }
    @Override public void submitMovingBlock(MatrixStack matrices, MovingBlockRenderState state) { }
    @Override public void submitCustom(OrderedRenderCommandQueue.LayeredCustom custom) { }

    private static final VertexConsumer DISCARD = new VertexConsumer() {
        @Override public VertexConsumer vertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer color(int argb) { return this; }
        @Override public VertexConsumer texture(float u, float v) { return this; }
        @Override public VertexConsumer overlay(int u, int v) { return this; }
        @Override public VertexConsumer light(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public VertexConsumer lineWidth(float width) { return this; }
    };
}
