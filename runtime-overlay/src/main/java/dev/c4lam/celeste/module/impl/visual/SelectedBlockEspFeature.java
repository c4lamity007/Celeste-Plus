package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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

/** Client-owned terrain index with immutable render snapshots; never requests chunks. */
public abstract class SelectedBlockEspFeature implements ModuleFeature {
    private static final int RENDER_SECTION_LIMIT = 4096;
    private static final Set<SelectedBlockEspFeature> ACTIVE =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final BlockEspScan scan = new BlockEspScan();
    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private Set<String> selection = Set.of();
    private Set<Block> selected = Set.of();
    private volatile List<BlockEspScan.SectionSnapshot> published = List.of();
    private volatile ClientWorld scannedWorld;
    private volatile boolean enabled;
    private boolean loadedChunks = true;
    private int range = BlockEspScan.DEFAULT_RADIUS, rgb = 0x74DFFF;
    private float opacity = .85f, fill = .08f, lineWidth = 1.3f;

    protected boolean useStorageIndex() { return false; }

    public final void initialize() {
        String key = useStorageIndex() ? "storage" : "blocks";
        BetaSubscriptions.register(ClientChunkEvents.CHUNK_LOAD, "celeste:esp/" + key + "/load",
            ClientChunkEvents.Load.class, (world, chunk) -> {
                if (enabled && scannedWorld == world) scan.invalidateChunk(chunk.getPos().x, chunk.getPos().z);
            }, ClientChunkEvents.CHUNK_LOAD::register);
        BetaSubscriptions.register(ClientChunkEvents.CHUNK_UNLOAD, "celeste:esp/" + key + "/unload",
            ClientChunkEvents.Unload.class, (world, chunk) -> {
                if (enabled && scannedWorld == world) scan.unloadChunk(chunk.getPos().x, chunk.getPos().z);
            }, ClientChunkEvents.CHUNK_UNLOAD::register);
    }

    /** Shared startup/late-attach callback. Apply packet changes on the next client tick. */
    public static void blockChanged(ClientWorld world, BlockPos pos) {
        if (ACTIVE.isEmpty()) return;
        for (SelectedBlockEspFeature feature : ACTIVE) if (feature.scannedWorld == world) {
            feature.scan.invalidate(new BlockEspScan.Section(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
        }
    }

    @Override public void onEnable(ModuleContext context) { clear(); enabled = true; ACTIVE.add(this); }
    @Override public void onDisable(ModuleContext context) { enabled = false; ACTIVE.remove(this); clear(); }
    @Override public void onWorldChanged(ModuleContext context) { clear(); }
    private void clear() { scan.clear(); published = List.of(); scannedWorld = null; }

    @Override public void onStartTick(ModuleContext context) {
        enabled = true;
        ACTIVE.add(this);
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
        loadedChunks = context.toggle("Loaded chunks", true);
        rgb = context.colorPick("Color", 0x74DFFF) & 0xFFFFFF;
        opacity = (float) finite(context.slider("Opacity", .85), .85, .1, 1);
        fill = (float) finite(context.slider("Fill opacity", .08), .08, 0, .3);
        lineWidth = (float) finite(context.slider("Line width", 1.3), 1.3, .75, 3);
        BlockPos center = player.getBlockPos();
        int chunks = loadedChunks ? Math.max(2, Math.min(32, context.client().options.getViewDistance().getValue())) + 3
            : (range + 15) / 16;
        scan.tick(world, new BlockEspScan.Point(center.getX(), center.getY(), center.getZ()), chunks,
            loadedChunks ? 0 : range, world.getBottomSectionCoord(), (world.getTopYInclusive() >> 4) + 1,
            new BlockEspScan.Probe() {
                private final Map<WorldChunk, Map<Integer, List<BlockPos>>> storageIndexes = new IdentityHashMap<>();
                private final Set<WorldChunk> dense = Collections.newSetFromMap(new IdentityHashMap<>());
                private Map<Integer, List<BlockPos>> storageIndex(WorldChunk chunk) {
                    if (chunk == null || dense.contains(chunk)) return null;
                    var cached = storageIndexes.get(chunk);
                    if (cached != null) return cached;
                    // Includes server-supplied pending NBT as well as initialized block entities.
                    // Materializing an existing chest later must not make its position invisible.
                    Set<BlockPos> positions = chunk.getBlockEntityPositions();
                    if (positions.size() > 512) { dense.add(chunk); return null; }
                    Map<Integer, List<BlockPos>> sections = new HashMap<>();
                    for (BlockPos pos : positions) if (selected.contains(chunk.getBlockState(pos).getBlock())) {
                        sections.computeIfAbsent(pos.getY() >> 4, ignored -> new ArrayList<>()).add(pos);
                    }
                    storageIndexes.put(chunk, sections);
                    return sections;
                }
                public boolean chunkLoaded(int x, int z) {
                    return world.isChunkLoaded(x, z)
                        && world.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false) != null;
                }
                public Object revision(BlockEspScan.Section section) { return section(world, section); }
                public boolean loaded(BlockEspScan.Section section) {
                    return section(world, section) != null;
                }
                public boolean mayMatch(BlockEspScan.Section section) {
                    ChunkSection states = section(world, section);
                    return states != null && !states.isEmpty()
                        && states.hasAny(state -> selected.contains(state.getBlock()));
                }
                public boolean indexed(BlockEspScan.Section section) {
                    ChunkSection states = section(world, section);
                    if (states == null || states.hasAny(state -> selected.contains(state.getBlock())
                            && !(state.getBlock() instanceof BlockEntityProvider))) return false;
                    var index = storageIndex(chunk(world, section));
                    // A palette match without an indexed position uses the ordinary bounded scan.
                    // Never advertise a cheap indexed pass and then walk 4,096 states inside it.
                    return index != null && index.containsKey(section.y());
                }
                public void collect(BlockEspScan.Section section, BlockEspScan.PointConsumer output) {
                    ChunkSection states = section(world, section);
                    if (states == null || states.isEmpty()) return;
                    if (indexed(section)) {
                        for (BlockPos pos : storageIndex(chunk(world, section)).get(section.y())) {
                            output.accept(new BlockEspScan.Point(pos.getX(), pos.getY(), pos.getZ()));
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
        published = scan.snapshotSections();
    }

    public final void extract(WorldExtractionContext context, List<VisualWorldRenderer.WorldBox> output) {
        var client = MinecraftClient.getInstance();
        if (!enabled || context == null || output == null || scannedWorld != context.world()
            || client.player == null || client.world != scannedWorld || !client.player.isAlive()) return;
        int drawn = 0, examined = 0, sectionsExamined = 0;
        Set<BlockPos> drawnPositions = new HashSet<>();
        outer: for (BlockEspScan.SectionSnapshot matches : published) {
            // Near-first snapshots retain all matches; a dense offscreen selection
            // must not turn each frame into a walk of the entire loaded world.
            if (sectionsExamined++ >= RENDER_SECTION_LIMIT) break;
            BlockEspScan.Section section = matches.section();
            if (!scannedWorld.isChunkLoaded(section.x(), section.z())) continue;
            int baseX = section.x() << 4, baseY = section.y() << 4, baseZ = section.z() << 4;
            Box sectionBox = new Box(baseX, baseY, baseZ, baseX + 16, baseY + 16, baseZ + 16);
            if (context.frustum() != null && !context.frustum().isVisible(sectionBox)) continue;
            for (int local = matches.nextLocalIndex(-1); local >= 0; local = matches.nextLocalIndex(local)) {
                if (drawn >= BlockEspScan.DRAW_LIMIT || examined++ >= BlockEspScan.RENDER_CANDIDATE_LIMIT) break outer;
                int x = baseX + (local & 15), y = baseY + (local >> 8), z = baseZ + (local >> 4 & 15);
                double dx = client.player.getX() - x - .5;
                double dy = client.player.getY() - y - .5;
                double dz = client.player.getZ() - z - .5;
                double distance = dx * dx + dy * dy + dz * dz;
                if (!loadedChunks && distance > (double) range * range) continue;
                BlockPos pos = new BlockPos(x, y, z);
                if (drawnPositions.contains(pos) || !scannedWorld.isChunkLoaded(pos)) continue;
                BlockState state = scannedWorld.getBlockState(pos);
                // Extraction can occur after a packet has removed/replaced a block
                // but before the next scanner tick.
                if (!selected.contains(state.getBlock())) continue;
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
                float fade = loadedChunks ? 1f : (float) Math.min(1, Math.max(0, (range - Math.sqrt(distance)) / 3));
                output.add(new VisualWorldRenderer.WorldBox(box, VisualWorldRenderer.argb(fill * fade, rgb),
                    VisualWorldRenderer.argb(opacity * fade, rgb), lineWidth));
                drawnPositions.add(pos);
                drawn++;
            }
        }
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static ChunkSection section(ClientWorld world, BlockEspScan.Section section) {
        if (world == null || section.y() < world.getBottomSectionCoord()
            || section.y() > (world.getTopYInclusive() >> 4)) return null;
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
