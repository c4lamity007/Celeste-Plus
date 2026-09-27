package dev.c4lam.celeste.module.impl.visual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure, bounded scheduler for scanning loaded chunk sections.
 *
 * <p>The old coordinate-shell walk spent most of its budget visiting air and
 * could take several seconds to reach the outside of a 48-block radius. This
 * scheduler asks the chunk palette whether a section can contain a selected
 * block before the caller walks its 4,096 states. It never requests or loads a
 * chunk.</p>
 */
public final class BlockEspScan {
    public static final int DEFAULT_RADIUS = 64;
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 128;
    public static final int PALETTE_BUDGET = 96;
    // Two positive sections are at most 8,192 state reads in one tick. Common
    // selections therefore fill the nearest cache immediately without a frame
    // spike, while rare blocks can skip 96 palette-negative sections per tick.
    public static final int MATCHED_SECTION_BUDGET = 2;
    public static final int CACHE_LIMIT = 1024;
    public static final int DRAW_LIMIT = 512;

    public record Point(int x, int y, int z) {
        long distance(Point other) {
            long dx = (long) x - other.x, dy = (long) y - other.y, dz = (long) z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Chunk X/Z and absolute section Y. */
    public record Section(int x, int y, int z) {
        boolean contains(Point point) {
            return Math.floorDiv(point.x, 16) == x && Math.floorDiv(point.y, 16) == y
                && Math.floorDiv(point.z, 16) == z;
        }
    }

    @FunctionalInterface
    public interface PointConsumer { void accept(Point point); }

    public interface Probe {
        /** True only when the client already has this section's chunk. */
        boolean loaded(Section section);

        /** Cheap palette-level test. No individual block walk belongs here. */
        boolean mayMatch(Section section);

        /** Emits matching positions from one section already known to match. */
        void collect(Section section, PointConsumer output);

        /** Revalidates a cached position so block changes disappear promptly. */
        boolean matches(Point point);
    }

    private final Set<Point> known = new LinkedHashSet<>();
    private List<Section> sections = List.of();
    private List<Point> published = List.of();
    private Object world;
    private Point anchor;
    private int radius;
    private int bottomSection;
    private int topSection;
    private int cursor;

    public void clear() {
        known.clear();
        sections = List.of();
        published = List.of();
        world = null;
        anchor = null;
        radius = cursor = 0;
        bottomSection = topSection = 0;
    }

    public List<Point> snapshot() { return published; }

    public void tick(Object identity, Point center, int requestedRadius, int requestedBottomSection,
                     int requestedTopSection, Probe probe) {
        if (identity == null || center == null || probe == null || requestedTopSection < requestedBottomSection) {
            clear();
            return;
        }
        int nextRadius = clampRadius(requestedRadius);
        boolean differentWorld = world != identity;
        if (differentWorld) clear();
        boolean rebuild = differentWorld || anchor == null || radius != nextRadius
            || bottomSection != requestedBottomSection || topSection != requestedTopSection
            || anchor.distance(center) > 64
            || sectionCoordinate(anchor.x) != sectionCoordinate(center.x)
            || sectionCoordinate(anchor.y) != sectionCoordinate(center.y)
            || sectionCoordinate(anchor.z) != sectionCoordinate(center.z);
        world = identity;
        radius = nextRadius;
        bottomSection = requestedBottomSection;
        topSection = requestedTopSection;
        if (rebuild) rebuild(center);

        long rangeSquared = (long) radius * radius;
        known.removeIf(point -> point.distance(center) > rangeSquared
            || !probe.loaded(sectionOf(point)) || !probe.matches(point));

        Comparator<Point> nearest = Comparator.comparingLong((Point point) -> point.distance(center))
            .thenComparingInt(Point::x).thenComparingInt(Point::y).thenComparingInt(Point::z);
        PriorityQueue<Point> farthest = new PriorityQueue<>(CACHE_LIMIT, nearest.reversed());
        farthest.addAll(known);

        int paletteChecks = 0;
        int matchedSections = 0;
        while (cursor < sections.size() && paletteChecks < PALETTE_BUDGET) {
            Section section = sections.get(cursor);
            if (!probe.loaded(section)) {
                cursor++;
                paletteChecks++;
                continue;
            }
            if (!probe.mayMatch(section)) {
                cursor++;
                paletteChecks++;
                continue;
            }
            if (matchedSections >= MATCHED_SECTION_BUDGET) break;
            cursor++;
            paletteChecks++;
            matchedSections++;
            probe.collect(section, point -> {
                if (point == null || !section.contains(point) || point.distance(center) > rangeSquared
                    || known.contains(point)) return;
                if (known.size() >= CACHE_LIMIT) {
                    Point last = farthest.peek();
                    if (last == null || nearest.compare(point, last) >= 0) return;
                    known.remove(farthest.remove());
                }
                known.add(point);
                farthest.add(point);
            });
        }
        if (cursor >= sections.size()) {
            // Repeat the loaded-section pass so newly placed blocks are found
            // without waiting for a player move or a module toggle.
            rebuild(center);
        }
        List<Point> sorted = new ArrayList<>(known);
        sorted.sort(nearest);
        published = List.copyOf(sorted);
    }

    static int clampRadius(int requestedRadius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, requestedRadius));
    }

    private void rebuild(Point center) {
        anchor = center;
        cursor = 0;
        int chunkRadius = (radius + 15) / 16;
        int centerChunkX = sectionCoordinate(center.x);
        int centerChunkZ = sectionCoordinate(center.z);
        int lowY = Math.max(bottomSection, sectionCoordinate(center.y - radius));
        int highY = Math.min(topSection, sectionCoordinate(center.y + radius));
        long rangeSquared = (long) radius * radius;
        ArrayList<Section> next = new ArrayList<>((chunkRadius * 2 + 1) * (chunkRadius * 2 + 1)
            * Math.max(1, highY - lowY + 1));
        for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
            for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                for (int sectionY = lowY; sectionY <= highY; sectionY++) {
                    Section section = new Section(chunkX, sectionY, chunkZ);
                    if (sectionDistance(section, center) <= rangeSquared) next.add(section);
                }
            }
        }
        next.sort(Comparator.comparingLong((Section section) -> sectionDistance(section, center))
            .thenComparingInt(Section::x).thenComparingInt(Section::y).thenComparingInt(Section::z));
        sections = List.copyOf(next);
    }

    static long sectionDistance(Section section, Point point) {
        int minX = section.x * 16, minY = section.y * 16, minZ = section.z * 16;
        long dx = axisDistance(point.x, minX, minX + 15);
        long dy = axisDistance(point.y, minY, minY + 15);
        long dz = axisDistance(point.z, minZ, minZ + 15);
        return dx * dx + dy * dy + dz * dz;
    }

    static Section sectionOf(Point point) {
        return new Section(sectionCoordinate(point.x), sectionCoordinate(point.y), sectionCoordinate(point.z));
    }

    private static int sectionCoordinate(int block) { return Math.floorDiv(block, 16); }

    private static long axisDistance(int value, int minimum, int maximum) {
        if (value < minimum) return (long) minimum - value;
        if (value > maximum) return (long) value - maximum;
        return 0;
    }
}
