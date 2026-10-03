package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

import java.util.List;

/** Crosshair selection shape filled by the shared animated, single-alpha material. */
public final class BlockHighlightFeature implements ModuleFeature {
    private static final int SHARED_QUAD_LIMIT = 768;
    private static final double SURFACE_OFFSET = 0.002;
    private volatile boolean enabled;
    private volatile double opacity = 0.35;
    private volatile int color = 0x74DFFF;
    private VoxelShape cachedShape;
    private List<Box> cachedBoxes = List.of();
    private List<BlockHighlightSolidVisualMesh.Face> cachedMesh = List.of();

    @Override public String name() { return "Block Highlight"; }
    @Override public void onEnable(ModuleContext context) { update(context); enabled = true; }
    @Override public void onStartTick(ModuleContext context) { update(context); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; clear(); }
    @Override public void onWorldChanged(ModuleContext context) { enabled = false; clear(); }

    private void update(ModuleContext context) {
        double configuredOpacity = context.slider("Opacity", 0.35);
        opacity = Double.isFinite(configuredOpacity) ? MathHelper.clamp(configuredOpacity, 0.05, 1) : 0.35;
        color = context.colorPick("Color", 0x74DFFF) & 0xFFFFFF;
    }

    public void extract(WorldExtractionContext context, List<BlockHighlightWorldGlowRenderer.Quad> quads) {
        extract(context, quads, SHARED_QUAD_LIMIT);
    }

    /** Append only the complete current hit shape; no retained draw state or world mutation. */
    public void extract(WorldExtractionContext context, List<BlockHighlightWorldGlowRenderer.Quad> quads, int quadLimit) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || context == null || quads == null || client.world == null || client.player == null
            || client.world != context.world() || client.player.isRemoved() || client.currentScreen != null
            || !(client.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            clear();
            return;
        }
        int available = Math.max(0, Math.min(SHARED_QUAD_LIMIT, quadLimit)) - quads.size();
        if (available < 6) return;
        BlockPos position = hit.getBlockPos();
        if (!context.world().isChunkLoaded(position)) { clear(); return; }
        var state = context.world().getBlockState(position);
        if (state.isAir()) { clear(); return; }
        VoxelShape shape = state.getOutlineShape(context.world(), position, ShapeContext.of(client.player));
        if (shape.isEmpty()) { clear(); return; }
        Box bounds = shape.getBoundingBox().offset(position);
        if (context.camera() == null || context.frustum() != null && !context.frustum().isVisible(bounds)) return;
        if (shape != cachedShape) {
            List<Box> boxes = shape.getBoundingBoxes();
            if (boxes.size() > BlockHighlightBlockHighlightMesh.MAX_BOXES) { clear(); return; }
            if (!boxes.equals(cachedBoxes)) {
                cachedBoxes = List.copyOf(boxes);
                cachedMesh = BlockHighlightBlockHighlightMesh.surface(boxes.stream().map(box -> new BlockHighlightBlockHighlightMesh.Cuboid(
                    box.minX - SURFACE_OFFSET, box.minY - SURFACE_OFFSET, box.minZ - SURFACE_OFFSET,
                    box.maxX + SURFACE_OFFSET, box.maxY + SURFACE_OFFSET, box.maxZ + SURFACE_OFFSET)).toList(),
                    BlockHighlightBlockHighlightMesh.MAX_FACES);
            }
            cachedShape = shape;
        }
        var localCamera = context.camera().getCameraPos().subtract(position.getX(), position.getY(), position.getZ());
        // Test the actual occupied shape, not the enclosing box's empty stair corner.
        for (Box box : cachedBoxes) if (box.contains(localCamera)) return;
        // Each voxel box is only 0.002 wider per side to avoid terrain z-fighting;
        // the union still preserves the step/slab silhouette and removes interiors.
        // Private surface depth resolves front/back faces before one alpha blend.
        int argb = Math.round((float) opacity * 255) << 24 | color;
        BlockHighlightSolidVisuals.faces(quads, BlockHighlightBlockHighlightMesh.materialize(cachedMesh,
            position.getX(), position.getY(), position.getZ(), argb, available), quadLimit);
    }

    private void clear() {
        cachedShape = null;
        cachedBoxes = List.of();
        cachedMesh = List.of();
    }
}
