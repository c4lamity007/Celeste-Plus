package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/** Client-thread scanning with immutable extraction snapshots, never chunk loads. */
abstract class SelectedBlockEspFeature implements ModuleFeature {
    private final BlockEspScan scan = new BlockEspScan();
    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private Set<String> selection = Set.of();
    private Set<Block> selected = Set.of();
    private volatile List<BlockEspScan.Point> published = List.of();
    private volatile ClientWorld scannedWorld;
    private volatile boolean enabled;
    private int range = BlockEspScan.DEFAULT_RADIUS, rgb = 0x74DFFF;
    private float opacity = .85f, fill = .08f, lineWidth = 1.3f;

    protected boolean useStorageIndex() { return false; }

    @Override public void onEnable(ModuleContext context) { clear(); enabled = true; }
    @Override public void onDisable(ModuleContext context) { enabled = false; clear(); }
    @Override public void onWorldChanged(ModuleContext context) { clear(); }
    private void clear() { scan.clear(); published = List.of(); scannedWorld = null; }

    @Override public void onStartTick(ModuleContext context) {
        enabled = true;
        var world = context.client().world;
        var player = context.client().player;
        if (world == null || player == null || !player.isAlive()) { clear(); return; }
        Set<String> ids = context.setting("Blocks") instanceof GuiSetting.BlockList blocks ? blocks.ids() : Set.of();
        if (!selection.equals(ids)) {
            selection = Set.copyOf(ids);
            Set<Block> next = new HashSet<>();
            for (String id : selection) {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier != null && Registries.BLOCK.containsId(identifier)) {
                    Block block = Registries.BLOCK.get(identifier);
                    if (!block.getDefaultState().isAir()) next.add(block);
                }
            }
            // A filled cauldron is still the same workstation selected in the picker.
            if (next.contains(Blocks.CAULDRON)) {
                next.add(Blocks.WATER_CAULDRON); next.add(Blocks.LAVA_CAULDRON); next.add(Blocks.POWDER_SNOW_CAULDRON);
            }
            selected = Set.copyOf(next);
            clear();
        }
        if (selected.isEmpty()) { clear(); return; }
        range = (int) finite(context.slider("Range", BlockEspScan.DEFAULT_RADIUS),
            BlockEspScan.DEFAULT_RADIUS, BlockEspScan.MIN_RADIUS, BlockEspScan.MAX_RADIUS);
        rgb = context.colorPick("Color", 0x74DFFF) & 0xFFFFFF;
        opacity = (float) finite(context.slider("Opacity", .85), .85, .1, 1);
        fill = (float) finite(context.slider("Fill opacity", .08), .08, 0, .3);
        lineWidth = (float) finite(context.slider("Line width", 1.3), 1.3, .75, 3);
        BlockPos center = player.getBlockPos();
        scan.tick(world, new BlockEspScan.Point(center.getX(), center.getY(), center.getZ()), range,
            world.getBottomSectionCoord(), world.getTopSectionCoord(),
            new BlockEspScan.Probe() {
                public boolean loaded(BlockEspScan.Section section) {
                    return section(world, section) != null;
                }
                public boolean mayMatch(BlockEspScan.Section section) {
                    ChunkSection states = section(world, section);
                    return states != null && !states.isEmpty()
                        && states.hasAny(state -> selected.contains(state.getBlock()));
                }
                public boolean indexed(BlockEspScan.Section section) {
                    if (!useStorageIndex()) return false;
                    ChunkSection states = section(world, section);
                    WorldChunk chunk = chunk(world, section);
                    // Workstations without a block entity still use the palette
                    // scanner. Very dense chunks stay on its bounded state walk.
                    return states != null && chunk != null && chunk.getBlockEntities().size() <= 512
                        && !states.hasAny(state -> selected.contains(state.getBlock())
                            && !(state.getBlock() instanceof BlockEntityProvider));
                }
                public void collect(BlockEspScan.Section section, BlockEspScan.PointConsumer output) {
                    ChunkSection states = section(world, section);
                    if (states == null || states.isEmpty()) return;
                    if (indexed(section)) {
                        WorldChunk chunk = chunk(world, section);
                        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                            if (Math.floorDiv(pos.getY(), 16) == section.y()
                                && selected.contains(chunk.getBlockState(pos).getBlock())) {
                                output.accept(new BlockEspScan.Point(pos.getX(), pos.getY(), pos.getZ()));
                            }
                        }
                        return;
                    }
                    int baseX = section.x() << 4, baseY = section.y() << 4, baseZ = section.z() << 4;
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        if (selected.contains(states.getBlockState(x, y, z).getBlock())) {
                            output.accept(new BlockEspScan.Point(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
                public boolean matches(BlockEspScan.Point point) {
                    scratch.set(point.x(), point.y(), point.z());
                    return selected.contains(world.getBlockState(scratch).getBlock());
                }
            });
        scannedWorld = world;
        published = scan.snapshot();
    }

    public final void extract(WorldExtractionContext context, List<VisualWorldRenderer.WorldBox> output) {
        var client = MinecraftClient.getInstance();
        if (!enabled || context == null || output == null || scannedWorld != context.world()
            || client.player == null || client.world != scannedWorld || !client.player.isAlive()) return;
        int drawn = 0;
        Set<BlockPos> drawnPositions = new HashSet<>();
        for (BlockEspScan.Point point : published) {
            if (drawn >= BlockEspScan.DRAW_LIMIT) break;
            BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
            if (drawnPositions.contains(pos) || !scannedWorld.isChunkLoaded(pos)) continue;
            BlockState state = scannedWorld.getBlockState(pos);
            // Extraction can occur after a packet has removed/replaced a block
            // but before the next scanner tick.
            if (!selected.contains(state.getBlock())) continue;
            double dx = client.player.getX() - point.x() - .5;
            double dy = client.player.getY() - point.y() - .5;
            double dz = client.player.getZ() - point.z() - .5;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > (double) range * range) continue;
            var shape = state.getOutlineShape(scannedWorld, pos, ShapeContext.of(client.player));
            // Explicitly selected fluids/invisible blocks have no selection
            // shape; keep their cell visible instead of silently hiding them.
            Box box = shape.isEmpty() ? new Box(pos) : shape.getBoundingBox().offset(pos);
            if (state.getBlock() instanceof ChestBlock && state.contains(ChestBlock.CHEST_TYPE)
                && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                BlockPos other = pos.offset(ChestBlock.getFacing(state));
                if (scannedWorld.isChunkLoaded(other)) {
                    BlockState partner = scannedWorld.getBlockState(other);
                    // Only join actual pairs, including pairs across chunk edges.
                    if (partner.isOf(state.getBlock()) && partner.contains(ChestBlock.CHEST_TYPE)
                        && partner.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE
                        && partner.get(ChestBlock.CHEST_TYPE) != state.get(ChestBlock.CHEST_TYPE)
                        && partner.get(ChestBlock.FACING) == state.get(ChestBlock.FACING)
                        && other.offset(ChestBlock.getFacing(partner)).equals(pos)) {
                        var otherShape = partner.getOutlineShape(scannedWorld, other, ShapeContext.of(client.player));
                        if (!otherShape.isEmpty()) {
                            box = box.union(otherShape.getBoundingBox().offset(other));
                            drawnPositions.add(other);
                        }
                    }
                }
            }
            box = box.expand(.002);
            if (context.frustum() != null && !context.frustum().isVisible(box)) continue;
            float fade = (float) Math.min(1, Math.max(0, (range - Math.sqrt(distance)) / 3));
            output.add(new VisualWorldRenderer.WorldBox(box, VisualWorldRenderer.argb(fill * fade, rgb),
                VisualWorldRenderer.argb(opacity * fade, rgb), lineWidth));
            drawnPositions.add(pos);
            drawn++;
        }
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static ChunkSection section(ClientWorld world, BlockEspScan.Section section) {
        if (world == null || section.y() < world.getBottomSectionCoord()
            || section.y() > world.getTopSectionCoord()) return null;
        WorldChunk chunk = chunk(world, section);
        if (chunk == null) return null;
        int index = world.sectionCoordToIndex(section.y());
        ChunkSection[] sections = chunk.getSectionArray();
        return index >= 0 && index < sections.length ? sections[index] : null;
    }

    private static WorldChunk chunk(ClientWorld world, BlockEspScan.Section section) {
        if (world == null || !world.isChunkLoaded(section.x(), section.z())) return null;
        return world.getChunkManager().getChunk(section.x(), section.z(), ChunkStatus.FULL, false);
    }
}
