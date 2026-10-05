package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.render.geometry.WorldBox;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.HoleEspWorldGlowRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded, nearest-first scanner for blast-resistant 1x1 and 1x2 combat holes. */
public final class HoleEspFeature implements ModuleFeature {
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final int MAX_CHECKS_PER_TICK = 192;
    private static final int MAX_HOLES = 256;
    private static final int MAX_TEXTURED_HOLES = 8; // Three quads each, independent of scan density.
    private static final int OBSIDIAN_COLOR = 0xD760FF;
    private static final int BEDROCK_COLOR = 0x68E4FF;

    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private final Map<Long, HolePosition> known = new HashMap<>();
    private volatile List<HolePosition> published = List.of();
    private volatile ClientWorld scannedWorld;
    private List<BlockPos> scanOffsets = List.of();
    private int offsetsRange = -1;

    private volatile boolean enabled;
    private boolean cycleActive;
    private boolean showObsidian = true;
    private boolean showBedrock = true;
    private WorldBox.Shape style = WorldBox.Shape.FOOTPRINT;
    private float opacity = 0.8F;
    private float fillOpacity = 0.14F;
    private float lineWidth = 1.5F;
    private double height = 0.6D;
    private boolean glow = true;
    private int obsidianColor = OBSIDIAN_COLOR;
    private int bedrockColor = BEDROCK_COLOR;
    private int range = 8;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private int cursor;

    @Override
    public String name() {
        return "Hole ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        enabled = true;
        updateSettings(context);
        resetAll();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        ClientWorld world = context.client().world;
        ClientPlayerEntity player = context.client().player;
        if (world == null || player == null || !player.isAlive()) {
            resetAll();
            return;
        }

        int oldRange = range;
        updateSettings(context);
        BlockPos center = player.getBlockPos();
        double teleportThreshold = Math.max(8.0D, range * 2.0D);
        double movedSquared = squaredDistance(anchorX, anchorY, anchorZ, center.getX(), center.getY(), center.getZ());
        if (scannedWorld != world || movedSquared > teleportThreshold * teleportThreshold) {
            resetAll();
        }
        scannedWorld = world;
        if (!cycleActive
            || oldRange != range
            || movedSquared > 16.0D) {
            beginCycle(center);
        }
        revalidate(world, player);
        scan(world);
        // Publish discoveries this tick, not after the entire scan volume finishes.
        published = known.values().stream().sorted(Comparator.comparingDouble(hole -> squaredDistance(
            player.getX(), player.getY(), player.getZ(),
            hole.x() + hole.sizeX() * 0.5D, hole.y() + 0.5D, hole.z() + hole.sizeZ() * 0.5D))).toList();
    }

    @Override
    public void onDisable(ModuleContext context) {
        enabled = false;
        resetAll();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        resetAll();
    }

    private void updateSettings(ModuleContext context) {
        range = MathHelper.clamp((int) Math.round(context.slider("Range", 8.0D)), 4, 16);
        showObsidian = context.toggle("Obsidian", true);
        showBedrock = context.toggle("Bedrock", true);
        style = switch (context.mode("Style", "Footprint")) {
            case "Box" -> WorldBox.Shape.BOX;
            case "Beacon" -> WorldBox.Shape.BEACON;
            default -> WorldBox.Shape.FOOTPRINT;
        };
        opacity = (float) MathHelper.clamp(context.slider("Opacity", 0.8D), 0.1D, 1.0D);
        fillOpacity = (float) MathHelper.clamp(context.slider("Fill opacity", 0.14D), 0.0D, 0.4D);
        height = MathHelper.clamp(context.slider("Height", 0.6D), 0.15D, 1.5D);
        lineWidth = (float) MathHelper.clamp(context.slider("Line width", 1.5D), 0.75D, 3.0D);
        glow = context.toggle("Glow", true);
        obsidianColor = context.colorPick("Obsidian color", OBSIDIAN_COLOR) & 0xFFFFFF;
        bedrockColor = context.colorPick("Bedrock color", BEDROCK_COLOR) & 0xFFFFFF;
    }

    private void beginCycle(BlockPos center) {
        if (offsetsRange != range) {
            List<BlockPos> offsets = new ArrayList<>();
            int rangeSquared = range * range;
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    for (int x = -range; x <= range; x++) {
                        if (x * x + y * y + z * z <= rangeSquared) {
                            offsets.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
            offsets.sort(Comparator.comparingInt(offset ->
                offset.getX() * offset.getX() + offset.getY() * offset.getY() + offset.getZ() * offset.getZ()));
            scanOffsets = List.copyOf(offsets);
            offsetsRange = range;
        }
        anchorX = center.getX();
        anchorY = center.getY();
        anchorZ = center.getZ();
        cursor = 0;
        cycleActive = true;
    }

    private void scan(ClientWorld world) {
        int end = Math.min(cursor + MAX_CHECKS_PER_TICK, scanOffsets.size());
        while (cursor < end) {
            BlockPos offset = scanOffsets.get(cursor++);
            int x = anchorX + offset.getX();
            int y = anchorY + offset.getY();
            int z = anchorZ + offset.getZ();
            HolePosition hole = classify(world, x, y, z);
            long key = BlockPos.asLong(x, y, z);
            if (hole != null && (known.size() < MAX_HOLES || known.containsKey(key))) {
                known.put(key, hole);
            }
        }
        cycleActive = cursor < scanOffsets.size();
    }

    private HolePosition classify(ClientWorld world, int x, int y, int z) {
        if (!isOpenColumn(world, x, y, z)) {
            return null;
        }
        int floor = resistance(world, x, y - 1, z);
        if (floor == 0) {
            return null;
        }
        boolean allBedrock = floor == 2;
        Direction opening = null;
        for (Direction direction : HORIZONTAL) {
            int nx = x + direction.getOffsetX();
            int nz = z + direction.getOffsetZ();
            int wall = resistance(world, nx, y, nz);
            if (wall == 0) {
                if (opening != null || !isOpenColumn(world, nx, y, nz)) {
                    return null;
                }
                opening = direction;
            } else {
                allBedrock &= wall == 2;
            }
        }
        if (opening == null) {
            return new HolePosition(x, y, z, 1, 1, allBedrock ? HoleType.BEDROCK : HoleType.OBSIDIAN);
        }
        // Own a pair only from its north/west cell: one joined outline, no seam.
        if (opening != Direction.EAST && opening != Direction.SOUTH) {
            return null;
        }
        int otherX = x + opening.getOffsetX();
        int otherZ = z + opening.getOffsetZ();
        int otherFloor = resistance(world, otherX, y - 1, otherZ);
        if (otherFloor == 0) {
            return null;
        }
        allBedrock &= otherFloor == 2;
        for (Direction direction : HORIZONTAL) {
            if (direction == opening.getOpposite()) continue;
            int wall = resistance(world, otherX + direction.getOffsetX(), y, otherZ + direction.getOffsetZ());
            if (wall == 0) return null;
            allBedrock &= wall == 2;
        }
        return new HolePosition(x, y, z, opening == Direction.EAST ? 2 : 1,
            opening == Direction.SOUTH ? 2 : 1, allBedrock ? HoleType.BEDROCK : HoleType.OBSIDIAN);
    }

    private boolean isOpenColumn(ClientWorld world, int x, int y, int z) {
        scratch.set(x, y, z);
        if (world.isOutOfHeightLimit(scratch) || !world.isChunkLoaded(scratch)
            || !world.getBlockState(scratch).isAir()) return false;
        scratch.set(x, y + 1, z);
        return !world.isOutOfHeightLimit(scratch) && world.getBlockState(scratch).isAir();
    }

    private int resistance(ClientWorld world, int x, int y, int z) {
        scratch.set(x, y, z);
        if (world.isOutOfHeightLimit(scratch) || !world.isChunkLoaded(scratch)) return 0;
        BlockState state = world.getBlockState(scratch);
        if (state.isOf(Blocks.BEDROCK)) return 2;
        return state.isOf(Blocks.OBSIDIAN) ? 1 : 0;
    }

    private void revalidate(ClientWorld world, ClientPlayerEntity player) {
        double rangeSquared = (double) (range + 1) * (range + 1);
        var iterator = known.entrySet().iterator();
        // MAX_HOLES caps this work independently from the discovery scan budget.
        while (iterator.hasNext()) {
            Map.Entry<Long, HolePosition> entry = iterator.next();
            HolePosition hole = entry.getValue();
            if (squaredDistance(player.getX(), player.getY(), player.getZ(),
                hole.x() + hole.sizeX() * 0.5D, hole.y() + 0.5D, hole.z() + hole.sizeZ() * 0.5D) > rangeSquared) {
                iterator.remove();
                continue;
            }
            HolePosition current = classify(world, hole.x(), hole.y(), hole.z());
            if (current == null) iterator.remove();
            else entry.setValue(current);
        }
    }

    public void extract(WorldExtractionContext context, List<WorldBox> output,
                 List<HoleEspWorldGlowRenderer.Quad> glows) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (!enabled || (!showBedrock && !showObsidian) || player == null || !player.isAlive()
            || player.getEntityWorld() != context.world() || scannedWorld != context.world()) {
            return;
        }

        double rangeSquared = (double) range * range;
        Vec3d position = player.getLerpedPos(context.tickCounter().getTickProgress(false));
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        int textured = 0;
        for (HolePosition hole : published) {
            double distanceSquared = squaredDistance(
                position.x, position.y, position.z,
                hole.x() + hole.sizeX() * 0.5D, hole.y() + 0.5D, hole.z() + hole.sizeZ() * 0.5D
            );
            if ((hole.type() == HoleType.BEDROCK && !showBedrock)
                || (hole.type() == HoleType.OBSIDIAN && !showObsidian)
                || distanceSquared > rangeSquared) {
                continue;
            }

            float fade = (float) MathHelper.clamp((range - Math.sqrt(distanceSquared)) / 2.0D, 0.0D, 1.0D);
            fade = fade * fade * (3.0F - 2.0F * fade);
            if (fade <= 0.002F) continue;
            int color = hole.type() == HoleType.BEDROCK ? bedrockColor : obsidianColor;
            double displayHeight = style == WorldBox.Shape.FOOTPRINT ? 0.0D : height;
            Box bounds = new Box(
                hole.x() + 0.035D, hole.y() + 0.025D, hole.z() + 0.035D,
                hole.x() + hole.sizeX() - 0.035D, hole.y() + 0.025D + displayHeight,
                hole.z() + hole.sizeZ() - 0.035D
            );
            boolean texturedHole = glow && textured < MAX_TEXTURED_HOLES && distanceSquared < 12.0D * 12.0D
                && (context.frustum() == null || context.frustum().isVisible(bounds.expand(0.16D)));
            output.add(new WorldBox(
                bounds,
                WorldBox.argb(fillOpacity * fade * (texturedHole ? 0.45F : 1.0F), color),
                WorldBox.argb(style == WorldBox.Shape.BEACON ? 0.0F : fillOpacity * fade * 0.25F, color),
                WorldBox.argb(opacity * fade * (texturedHole ? 0.62F : 1.0F), color),
                lineWidth,
                style,
                false
            ));
            if (texturedHole) {
                appendField(glows, hole, color, fade, time);
                textured++;
            }
        }
    }

    /** Soft floor membrane and thin moving energy edges, never an opaque wall of bloom. */
    private void appendField(List<HoleEspWorldGlowRenderer.Quad> glows, HolePosition hole, int color,
                             float fade, double time) {
        double x1 = hole.x() + 0.045D;
        double x2 = hole.x() + hole.sizeX() - 0.045D;
        double z1 = hole.z() + 0.045D;
        double z2 = hole.z() + hole.sizeZ() - 0.045D;
        double y = hole.y() + 0.035D;
        int accent = hole.type() == HoleType.BEDROCK ? 0xD4FCFF : 0xF5B3FF;
        float strength = opacity * fade;
        glows.add(new HoleEspWorldGlowRenderer.Quad(HoleEspWorldGlowRenderer.Texture.PULSE,
            new Vec3d(x1, y, z1), new Vec3d(x2, y, z1),
            new Vec3d(x2, y, z2), new Vec3d(x1, y, z2),
            WorldBox.argb(strength * 0.34F, color),
            WorldBox.argb(strength * 0.30F, accent)));
        double phase = time * 1.15D + hole.x() * 0.71D + hole.z() * 0.39D;
        double bandHeight = style == WorldBox.Shape.FOOTPRINT ? 0.13D : Math.min(0.42D, height * 0.65D);
        double lift = style == WorldBox.Shape.FOOTPRINT ? 0.0D
            : (0.5D + 0.5D * Math.sin(phase)) * Math.max(0.0D, height - bandHeight);
        double bottom = y + lift;
        double top = bottom + bandHeight;
        int startColor = WorldBox.argb(strength * 0.50F, color);
        int endColor = WorldBox.argb(strength * 0.42F, accent);
        // Keep the long edges parallel to the pair direction, with no bright seam in 1x2 holes.
        if (hole.sizeZ() > hole.sizeX()) {
            glows.add(new HoleEspWorldGlowRenderer.Quad(HoleEspWorldGlowRenderer.Texture.RIBBON,
                new Vec3d(x1, bottom, z1), new Vec3d(x1, bottom, z2),
                new Vec3d(x1, top, z2), new Vec3d(x1, top, z1), startColor, endColor));
            glows.add(new HoleEspWorldGlowRenderer.Quad(HoleEspWorldGlowRenderer.Texture.RIBBON,
                new Vec3d(x2, bottom, z2), new Vec3d(x2, bottom, z1),
                new Vec3d(x2, top, z1), new Vec3d(x2, top, z2), endColor, startColor));
        } else {
            glows.add(new HoleEspWorldGlowRenderer.Quad(HoleEspWorldGlowRenderer.Texture.RIBBON,
                new Vec3d(x1, bottom, z1), new Vec3d(x2, bottom, z1),
                new Vec3d(x2, top, z1), new Vec3d(x1, top, z1), startColor, endColor));
            glows.add(new HoleEspWorldGlowRenderer.Quad(HoleEspWorldGlowRenderer.Texture.RIBBON,
                new Vec3d(x2, bottom, z2), new Vec3d(x1, bottom, z2),
                new Vec3d(x1, top, z2), new Vec3d(x2, top, z2), endColor, startColor));
        }
    }

    private void resetScan() {
        cycleActive = false;
        cursor = 0;
    }

    private void resetAll() {
        resetScan();
        known.clear();
        published = List.of();
        scannedWorld = null;
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx;
        double y = ay - by;
        double z = az - bz;
        return x * x + y * y + z * z;
    }

    private enum HoleType {
        OBSIDIAN,
        BEDROCK
    }

    private record HolePosition(int x, int y, int z, int sizeX, int sizeZ, HoleType type) {
    }
}
