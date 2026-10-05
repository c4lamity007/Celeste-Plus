package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.render.geometry.WorldLineStrip;
import dev.c4lam.celeste.render.geometry.WorldBox;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Incremental ore scanner; never performs the supplied Volt full-volume scan in one tick. */
public final class OreEspExpansionFeature implements ModuleFeature {
    private static final int MAX_CURSOR_STEPS_PER_TICK = 768;
    private static final int MAX_DETECTED = 2_048;
    private static final int MAX_RENDERED = 192;
    private static final int MAX_DETAILED = 40;
    private static final int MAX_TEXTURED = 12; // Two layered quads each; no per-ore render layers.

    private final BlockPos.Mutable scratch = new BlockPos.Mutable();
    private final Map<Long, OreType> working = new HashMap<>();
    private volatile List<OrePosition> renderOres = List.of();
    private volatile boolean enabled;
    private int range = 16;
    private int anchorX;
    private int anchorY;
    private int anchorZ;
    private int cursorShell;
    private int cursorIndex;
    private boolean cycleActive;
    private boolean diamond = true;
    private boolean emerald = true;
    private boolean gold = true;
    private boolean iron = true;
    private boolean copper = true;
    private boolean redstone = true;
    private boolean lapis = true;
    private boolean coal = true;
    private boolean ancientDebris = true;
    private boolean netherGold;
    private boolean quartz;
    private String style = "Corners";
    private String colors = "Ore";
    private int customColor = 0xFF4FA3;
    private double opacity = 0.85D;
    private double fillOpacity = 0.06D;
    private float lineWidth = 1.2F;
    private double glow = 0.35D;

    @Override
    public String name() {
        return "Ore ESP";
    }

    @Override
    public void onEnable(ModuleContext context) {
        update(context);
        enabled = true;
        resetAll();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        ClientPlayerEntity player = context.client().player;
        ClientWorld world = context.client().world;
        if (player == null || world == null) {
            resetAll();
            return;
        }

        int oldRange = range;
        int oldTypes = enabledTypes();
        update(context);
        BlockPos center = player.getBlockPos();
        if (!cycleActive || oldRange != range || oldTypes != enabledTypes()
            || squaredDistance(center, anchorX, anchorY, anchorZ) > 64.0D) {
            beginCycle(center);
        }
        scan(world);
        rebuildRenderBoxes(player);
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

    public void extract(WorldExtractionContext context, List<WorldBox> boxes,
                 List<WorldLineStrip> lines) {
        extract(context, boxes, lines, null);
    }

    public void extract(WorldExtractionContext context, List<WorldBox> boxes,
                 List<WorldLineStrip> lines, List<OreEspWorldGlowRenderer.Quad> glows) {
        extract(context, boxes, lines, glows, 768);
    }

    public void extract(WorldExtractionContext context, List<WorldBox> boxes,
                 List<WorldLineStrip> lines, List<OreEspWorldGlowRenderer.Quad> glows, int quadLimit) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (!enabled || player == null || player.getEntityWorld() != context.world()) return;
        // Block queries remain tick-bounded; only presentation/fades follow the render frame.
        Geometry frame = buildGeometry(player.getLerpedPos(context.tickCounter().getTickProgress(false)), renderOres);
        boxes.addAll(frame.boxes());
        lines.addAll(frame.lines());
        if (glows != null && glow > 0.01D) {
            appendResonance(context, player.getLerpedPos(context.tickCounter().getTickProgress(false)), glows,
                Math.min(768, Math.max(0, quadLimit)));
        }
    }

    private void update(ModuleContext context) {
        range = MathHelper.clamp((int) Math.round(context.slider("Range", 16.0D)), 4, 32);
        diamond = context.toggle("Diamond", true);
        emerald = context.toggle("Emerald", true);
        gold = context.toggle("Gold", true);
        iron = context.toggle("Iron", true);
        copper = context.toggle("Copper", true);
        redstone = context.toggle("Redstone", true);
        lapis = context.toggle("Lapis", true);
        coal = context.toggle("Coal", true);
        ancientDebris = context.toggle("Ancient Debris", true);
        netherGold = context.toggle("Nether Gold", false);
        quartz = context.toggle("Quartz", false);
        style = context.mode("Style", "Corners");
        colors = context.mode("Colors", "Ore");
        customColor = context.colorPick("Color", 0xFF4FA3) & 0xFFFFFF;
        opacity = MathHelper.clamp(context.slider("Opacity", 0.85D), 0.1D, 1.0D);
        fillOpacity = MathHelper.clamp(context.slider("Fill opacity", 0.06D), 0.0D, 0.3D);
        lineWidth = (float) MathHelper.clamp(context.slider("Line width", 1.2D), 0.75D, 3.0D);
        glow = MathHelper.clamp(context.slider("Glow", 0.35D), 0.0D, 1.0D);
    }

    private void beginCycle(BlockPos center) {
        anchorX = center.getX();
        anchorY = center.getY();
        anchorZ = center.getZ();
        cursorShell = 0;
        cursorIndex = 0;
        cycleActive = true;
    }

    private void scan(ClientWorld world) {
        int steps = 0;
        int radiusSquared = range * range;
        while (cycleActive && steps++ < MAX_CURSOR_STEPS_PER_TICK) {
            if (cursorShell > range) {
                cycleActive = false;
                return;
            }
            long offset = scanOffset(cursorShell, cursorIndex++);
            if (cursorIndex >= shellSize(cursorShell)) {
                cursorShell++;
                cursorIndex = 0;
            }
            int dx = BlockPos.unpackLongX(offset);
            int dy = BlockPos.unpackLongY(offset);
            int dz = BlockPos.unpackLongZ(offset);
            if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                continue;
            }

            int x = anchorX + dx;
            int y = anchorY + dy;
            int z = anchorZ + dz;
            scratch.set(x, y, z);
            if (y < world.getBottomY() || y >= world.getBottomY() + world.getHeight()
                || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            OreType type = classify(world.getBlockState(scratch).getBlock());
            long packed = BlockPos.asLong(x, y, z);
            if (type == null || !isEnabled(type)) {
                working.remove(packed);
            } else if (working.containsKey(packed) || working.size() < MAX_DETECTED) {
                working.put(packed, type);
            }
        }
    }

    static int shellSize(int shell) {
        return shell == 0 ? 1 : 24 * shell * shell + 2;
    }

    /** Visits each cube-shell cell once, expanding from the player instead of the bottom corner. */
    static long scanOffset(int shell, int index) {
        if (shell == 0) return BlockPos.asLong(0, 0, 0);
        int side = shell * 2 + 1;
        int face = side * side;
        int band = (side - 2) * side;
        int core = (side - 2) * (side - 2);
        if (index < face * 2) {
            int sign = index < face ? -1 : 1;
            int local = index % face;
            return BlockPos.asLong(sign * shell, local / side - shell, local % side - shell);
        }
        index -= face * 2;
        if (index < band * 2) {
            int sign = index < band ? -1 : 1;
            int local = index % band;
            return BlockPos.asLong(local / side - shell + 1, sign * shell, local % side - shell);
        }
        index -= band * 2;
        int sign = index < core ? -1 : 1;
        int local = index % core;
        return BlockPos.asLong(local / (side - 2) - shell + 1, local % (side - 2) - shell + 1, sign * shell);
    }

    private void rebuildRenderBoxes(ClientPlayerEntity player) {
        double rangeSquared = (double) range * range;
        working.entrySet().removeIf(entry -> !isEnabled(entry.getValue())
            || squaredDistance(player.getX(), player.getY(), player.getZ(),
                BlockPos.unpackLongX(entry.getKey()) + 0.5D, BlockPos.unpackLongY(entry.getKey()) + 0.5D,
                BlockPos.unpackLongZ(entry.getKey()) + 0.5D) > rangeSquared);
        List<OrePosition> candidates = working.entrySet().stream()
            .map(entry -> new OrePosition(BlockPos.unpackLongX(entry.getKey()), BlockPos.unpackLongY(entry.getKey()),
                BlockPos.unpackLongZ(entry.getKey()), entry.getValue()))
            .sorted(Comparator.comparingInt((OrePosition ore) -> ore.type().priority()).reversed()
                .thenComparingDouble(ore -> squaredDistance(player.getX(), player.getY(), player.getZ(), ore.x(), ore.y(), ore.z())))
            .limit(MAX_RENDERED)
            .toList();
        List<OrePosition> visible = new ArrayList<>(candidates.size());
        for (OrePosition ore : candidates) {
            scratch.set(ore.x(), ore.y(), ore.z());
            // A mined/replaced block disappears on the next tick, not the next full scan cycle.
            if (!player.getEntityWorld().isChunkLoaded(ore.x() >> 4, ore.z() >> 4)
                || classify(player.getEntityWorld().getBlockState(scratch).getBlock()) != ore.type()) {
                working.remove(BlockPos.asLong(ore.x(), ore.y(), ore.z()));
                continue;
            }
            visible.add(ore);
        }
        renderOres = List.copyOf(visible);
    }

    private Geometry buildGeometry(Vec3d player, List<OrePosition> visible) {
        List<WorldBox> boxes = new ArrayList<>(visible.size());
        List<WorldLineStrip> lines = new ArrayList<>();
        int detailed = 0;
        for (OrePosition ore : visible) {
            double distance = Math.sqrt(squaredDistance(player.x, player.y, player.z,
                ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D));
            double fade = MathHelper.clamp((range - distance) / Math.max(2.0D, range * 0.18D), 0.0D, 1.0D);
            fade = fade * fade * (3.0D - 2.0D * fade);
            if (fade <= 0.002D) continue;
            int rgb = palette(ore.type());
            double alpha = opacity * fade;
            boolean highDetail = distance <= 12.0D && detailed < MAX_DETAILED;
            if (highDetail) detailed++;
            Box bounds = new Box(ore.x() + 0.035D, ore.y() + 0.035D, ore.z() + 0.035D,
                ore.x() + 0.965D, ore.y() + 0.965D, ore.z() + 0.965D);

            if ("Diamond".equals(style)) {
                diamond(lines, new Vec3d(ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D), rgb, alpha, 0.0D);
            } else if ("Corners".equals(style) && highDetail) {
                if (fillOpacity > 0.0D) {
                    boxes.add(new WorldBox(bounds,
                        dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (fillOpacity * alpha), rgb), 0, lineWidth));
                }
                corners(lines, bounds, rgb, alpha, 0.0D);
            } else if ("Corners".equals(style)) {
                // Compact distant markers avoid hundreds of overlapping full cube outlines.
                diamond(lines, new Vec3d(ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D), rgb, alpha * 0.62D, 0.0D);
            } else {
                boxes.add(new WorldBox(bounds,
                    dev.c4lam.celeste.render.geometry.WorldBox.argb((float) (fillOpacity * alpha), rgb),
                    dev.c4lam.celeste.render.geometry.WorldBox.argb((float) alpha, rgb), lineWidth));
            }
        }
        return new Geometry(List.copyOf(boxes), List.copyOf(lines));
    }

    /** Nearby ore cores use a rotating spectral membrane and a flowing texture, not fat lines. */
    private void appendResonance(WorldExtractionContext context, Vec3d player, List<OreEspWorldGlowRenderer.Quad> glows, int quadLimit) {
        double time = Util.getMeasuringTimeMs() / 1000.0D;
        Vector3f right = context.camera().getRotation().transform(new Vector3f(1F, 0F, 0F));
        Vector3f up = context.camera().getRotation().transform(new Vector3f(0F, 1F, 0F));
        Vec3d axisU = new Vec3d(right.x, right.y, right.z);
        Vec3d axisV = new Vec3d(up.x, up.y, up.z);
        List<OrePosition> nearest = renderOres.stream()
            .filter(ore -> squaredDistance(player.x, player.y, player.z,
                ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D) <= 12.0D * 12.0D)
            .filter(ore -> context.frustum() == null || context.frustum().isVisible(
                new Box(ore.x(), ore.y(), ore.z(), ore.x() + 1D, ore.y() + 1D, ore.z() + 1D)))
            .sorted(Comparator.comparingDouble(ore -> squaredDistance(player.x, player.y, player.z,
                ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D)))
            .limit(MAX_TEXTURED).toList();
        for (OrePosition ore : nearest) {
            // Both layers describe one marker; reserve the whole pair.
            if (glows.size() + 2 > quadLimit) break;
            Vec3d center = new Vec3d(ore.x() + 0.5D, ore.y() + 0.5D, ore.z() + 0.5D);
            double distance = player.distanceTo(center);
            double fade = MathHelper.clamp((range - distance) / Math.max(2.0D, range * 0.18D), 0.0D, 1.0D);
            fade = fade * fade * (3.0D - 2.0D * fade);
            fade *= MathHelper.clamp((12.0D - distance) / 2.0D, 0.0D, 1.0D);
            if (fade <= 0.002D) continue;
            int color = palette(ore.type());
            int accent = OreEspVisualEffects.mix(color, 0xF1FCFF, 0.52D);
            double phase = time * 0.38D + ore.x() * 0.23D + ore.y() * 0.71D + ore.z() * 0.41D;
            Vec3d u = axisU.multiply(Math.cos(phase)).add(axisV.multiply(Math.sin(phase)));
            Vec3d v = axisV.multiply(Math.cos(phase)).subtract(axisU.multiply(Math.sin(phase)));
            float strength = (float) (opacity * glow * fade);
            double radius = 0.47D + 0.022D * Math.sin(time * 1.7D + phase);
            texturedQuad(glows, OreEspWorldGlowRenderer.Texture.PULSE, center,
                u.multiply(radius), v.multiply(radius),
                dev.c4lam.celeste.render.geometry.WorldBox.argb(strength * 0.74F, color),
                dev.c4lam.celeste.render.geometry.WorldBox.argb(strength * 0.62F, accent));
            texturedQuad(glows, OreEspWorldGlowRenderer.Texture.RIBBON, center,
                axisU.multiply(0.68D), axisV.multiply(0.19D),
                dev.c4lam.celeste.render.geometry.WorldBox.argb(strength * 0.90F, color),
                dev.c4lam.celeste.render.geometry.WorldBox.argb(strength * 0.78F, accent));
        }
    }

    private static void texturedQuad(List<OreEspWorldGlowRenderer.Quad> glows, OreEspWorldGlowRenderer.Texture texture,
                                     Vec3d center, Vec3d u, Vec3d v, int color, int accent) {
        glows.add(new OreEspWorldGlowRenderer.Quad(texture,
            center.subtract(u).subtract(v), center.add(u).subtract(v),
            center.add(u).add(v), center.subtract(u).add(v), color, accent));
    }

    private int palette(OreType type) {
        return switch (colors) {
            case "Celeste" -> OreEspVisualEffects.mix(0xB18BFF, 0xFF65AD, type.priority() / 10.0D);
            case "Ice" -> OreEspVisualEffects.mix(0x829BDF, 0x99EEFF, type.priority() / 10.0D);
            case "Mono" -> OreEspVisualEffects.mix(0xA8B0C3, 0xF3F6FF, type.priority() / 10.0D);
            case "Custom" -> customColor;
            default -> type.rgb();
        };
    }

    private void corners(List<WorldLineStrip> lines, Box box,
                         int color, double alpha, double halo) {
        double length = 0.22D;
        for (int corner = 0; corner < 8; corner++) {
            boolean right = (corner & 1) != 0;
            boolean top = (corner & 2) != 0;
            boolean back = (corner & 4) != 0;
            Vec3d point = new Vec3d(right ? box.maxX : box.minX,
                top ? box.maxY : box.minY, back ? box.maxZ : box.minZ);
            OreEspVisualEffects.line(lines, List.of(point.add(right ? -length : length, 0.0D, 0.0D),
                point, point.add(0.0D, top ? -length : length, 0.0D)), color, alpha, lineWidth, halo);
            OreEspVisualEffects.line(lines, List.of(point, point.add(0.0D, 0.0D, back ? -length : length)),
                color, alpha, lineWidth, halo);
        }
    }

    private void diamond(List<WorldLineStrip> lines, Vec3d center,
                         int color, double alpha, double halo) {
        Vec3d top = center.add(0.0D, 0.38D, 0.0D);
        Vec3d bottom = center.add(0.0D, -0.38D, 0.0D);
        Vec3d north = center.add(0.0D, 0.0D, -0.28D);
        Vec3d south = center.add(0.0D, 0.0D, 0.28D);
        Vec3d east = center.add(0.28D, 0.0D, 0.0D);
        Vec3d west = center.add(-0.28D, 0.0D, 0.0D);
        OreEspVisualEffects.line(lines, List.of(north, east, south, west, north), color, alpha * 0.8D, lineWidth, halo);
        OreEspVisualEffects.line(lines, List.of(north, top, south, bottom, north), color, alpha, lineWidth, halo);
        OreEspVisualEffects.line(lines, List.of(east, top, west, bottom, east), color, alpha, lineWidth, halo);
    }

    private boolean isEnabled(OreType type) {
        return switch (type) {
            case DIAMOND -> diamond;
            case EMERALD -> emerald;
            case GOLD -> gold;
            case IRON -> iron;
            case COPPER -> copper;
            case REDSTONE -> redstone;
            case LAPIS -> lapis;
            case COAL -> coal;
            case ANCIENT_DEBRIS -> ancientDebris;
            case NETHER_GOLD -> netherGold;
            case QUARTZ -> quartz;
        };
    }

    private int enabledTypes() {
        int mask = 0;
        for (OreType type : OreType.values()) {
            if (isEnabled(type)) mask |= 1 << type.ordinal();
        }
        return mask;
    }

    private static OreType classify(Block block) {
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) return OreType.DIAMOND;
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) return OreType.EMERALD;
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) return OreType.GOLD;
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) return OreType.IRON;
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) return OreType.COPPER;
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) return OreType.REDSTONE;
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) return OreType.LAPIS;
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) return OreType.COAL;
        if (block == Blocks.ANCIENT_DEBRIS) return OreType.ANCIENT_DEBRIS;
        if (block == Blocks.NETHER_GOLD_ORE) return OreType.NETHER_GOLD;
        if (block == Blocks.NETHER_QUARTZ_ORE) return OreType.QUARTZ;
        return null;
    }

    private void resetAll() {
        cycleActive = false;
        working.clear();
        renderOres = List.of();
    }

    private static double squaredDistance(BlockPos pos, int x, int y, int z) {
        return squaredDistance(pos.getX(), pos.getY(), pos.getZ(), x, y, z);
    }

    private static double squaredDistance(double ax, double ay, double az, double bx, double by, double bz) {
        double x = ax - bx;
        double y = ay - by;
        double z = az - bz;
        return x * x + y * y + z * z;
    }

    private enum OreType {
        DIAMOND(0x62EAF2, 10), EMERALD(0x39E67D, 9), ANCIENT_DEBRIS(0xD78D72, 9),
        GOLD(0xFFD34F, 8), NETHER_GOLD(0xFFB23D, 7), REDSTONE(0xFF4A55, 7),
        LAPIS(0x4B78FF, 6), IRON(0xE7D9C8, 5), COPPER(0xE78B54, 4),
        QUARTZ(0xF4E7DF, 3), COAL(0xA4AEC2, 2);

        private final int rgb;
        private final int priority;

        OreType(int rgb, int priority) {
            this.rgb = rgb;
            this.priority = priority;
        }

        int rgb() { return rgb; }
        int priority() { return priority; }
    }

    private record OrePosition(int x, int y, int z, OreType type) {
    }

    private record Geometry(List<WorldBox> boxes,
                            List<WorldLineStrip> lines) {
    }
}
