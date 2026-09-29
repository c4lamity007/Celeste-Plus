package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A conservative, incremental graph of places a standing player can walk or jump to. */
public final class GroundWalkability {
    private static final double EPS = 1.0e-5;
    private static final double JUMP_HEIGHT = 1.25;
    private static final double STEP_HEIGHT = .6;
    private static final int NODE_LIMIT = 8192;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private GroundWalkability() { }

    /** Separate half/slab/stair surfaces inside one block; exact for vanilla sixteenths. */
    public static int heightLevel(double topY) {
        if (!Double.isFinite(topY)) throw new IllegalArgumentException("Invalid surface height");
        double fraction = topY - Math.floor(topY - .015);
        return Math.max(0, Math.min(31, (int) Math.round(fraction * 32) - 1));
    }

    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        boolean intersects(Box other) {
            return maxX > other.minX + EPS && minX < other.maxX - EPS
                && maxY > other.minY + EPS && minY < other.maxY - EPS
                && maxZ > other.minZ + EPS && minZ < other.maxZ - EPS;
        }
        Box union(Box other) {
            return new Box(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
        }
    }

    public record Cell(boolean loaded, List<Box> boxes, List<Box> supportBoxes) {
        public Cell(boolean loaded, List<Box> boxes) { this(loaded, boxes, boxes); }
        public Cell(boolean loaded, List<Box> boxes, boolean supportsStanding) {
            this(loaded, boxes, supportsStanding ? boxes : List.of());
        }
        public Cell { boxes = List.copyOf(boxes); supportBoxes = List.copyOf(supportBoxes); }
        public boolean supportsStanding() { return !supportBoxes.isEmpty(); }
    }
    @FunctionalInterface public interface Probe { Cell cell(int x, int y, int z); }
    public record Surface(int x, int y, int z, double topY) { }
    private record Key(int x, long height, int z) { }
    private record Node(int x, double topY, int z, double centerX, double centerZ) {
        Key key() { return new Key(x, Math.round(topY * 4096), z); }
    }
    private static final class Cursor {
        final Node node;
        int direction;
        Cursor(Node node) { this.node = node; }
    }

    public static final class Search {
        private final double seedX, seedY, seedZ, halfWidth, height;
        private final int radius, verticalRadius, centerX, centerY, centerZ;
        private final ArrayDeque<Cursor> frontier = new ArrayDeque<>();
        private final Set<Key> visited = new HashSet<>();
        private final LinkedHashSet<Surface> surfaces = new LinkedHashSet<>();
        private boolean seeded;

        public Search(double seedX, double seedY, double seedZ, double width, double height,
                      int radius, int verticalRadius) {
            if (!Double.isFinite(seedX) || !Double.isFinite(seedY) || !Double.isFinite(seedZ)
                || !Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || width > 2 || height > 4)
                throw new IllegalArgumentException("Invalid player bounds");
            this.seedX = seedX;
            this.seedY = seedY;
            this.seedZ = seedZ;
            this.halfWidth = width * .5;
            this.height = Math.max(1.8, height);
            this.radius = Math.max(1, Math.min(48, radius));
            this.verticalRadius = Math.max(2, Math.min(32, verticalRadius));
            centerX = floor(seedX);
            centerY = floor(seedY);
            centerZ = floor(seedZ);
        }

        /** The first stance uses actual feet, including when standing near a block edge. */
        public boolean seed(Probe probe) {
            if (seeded) return true;
            List<Double> tops = tops(centerX, centerZ, seedY - .125, seedY + .125, probe);
            tops.sort((a, b) -> Double.compare(Math.abs(a - seedY), Math.abs(b - seedY)));
            for (double top : tops) {
                Node node = new Node(centerX, top, centerZ, seedX, seedZ);
                if (standable(node, probe)) {
                    // All reads precede mutation, so a probe's exhausted budget is retryable.
                    List<Surface> support = supports(node.centerX, top, node.centerZ, EPS * 4, probe);
                    add(node, support);
                    seeded = true;
                    return true;
                }
            }
            return false;
        }

        /** One budget unit is one neighboring edge. An interrupted edge is retried next tick. */
        public int advance(Probe probe, int edgeBudget) {
            if (!seeded || edgeBudget <= 0) return 0;
            int edges = 0;
            while (!frontier.isEmpty() && edges < Math.min(1024, edgeBudget) && visited.size() < NODE_LIMIT) {
                Cursor cursor = frontier.peekFirst();
                Node from = cursor.node;
                int x = from.x + DX[cursor.direction], z = from.z + DZ[cursor.direction];
                if (Math.abs(x - centerX) <= radius && Math.abs(z - centerZ) <= radius) {
                    List<Double> tops = tops(x, z, from.topY - JUMP_HEIGHT, from.topY + JUMP_HEIGHT, probe);
                    for (double top : tops) {
                        if (Math.abs(top - seedY) > verticalRadius) continue;
                        Node to = new Node(x, top, z, x + .5, z + .5);
                        if (visited.contains(to.key()) || !standable(to, probe) || !crossable(from, to, probe)) continue;
                        List<Surface> support = supports(to.centerX, top, to.centerZ, EPS * 4, probe);
                        add(to, support);
                    }
                }
                edges++;
                if (++cursor.direction == DX.length) frontier.removeFirst();
            }
            return edges;
        }

        public boolean complete() { return seeded && (frontier.isEmpty() || visited.size() >= NODE_LIMIT); }
        public boolean reached(double x, double feetY, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(feetY) || !Double.isFinite(z)) return false;
            int cellX = floor(x), cellZ = floor(z);
            for (Surface surface : surfaces)
                if (surface.x == cellX && surface.z == cellZ && Math.abs(surface.topY - feetY) <= .15) return true;
            return false;
        }
        public List<Surface> surfaces() { return List.copyOf(surfaces); }
        public int surfaceCount() { return surfaces.size(); }
        public int centerX() { return centerX; }
        public int centerY() { return centerY; }
        public int centerZ() { return centerZ; }
        public int radius() { return radius; }
        public int verticalRadius() { return verticalRadius; }

        private void add(Node node, List<Surface> support) {
            if (visited.size() >= NODE_LIMIT) return;
            if (visited.add(node.key())) {
                frontier.addLast(new Cursor(node));
                surfaces.addAll(support);
            }
        }

        private boolean standable(Node node, Probe probe) {
            return clear(body(node.centerX, node.topY, node.centerZ), probe)
                && !supports(node.centerX, node.topY, node.centerZ, EPS * 4, probe).isEmpty();
        }

        private boolean crossable(Node from, Node to, Probe probe) {
            double delta = to.topY - from.topY;
            if (delta > JUMP_HEIGHT + EPS || delta < -JUMP_HEIGHT - EPS) return false;
            // Ordinary steps lift/drop by at most vanilla step height, with support all along.
            if (Math.abs(delta) <= STEP_HEIGHT + EPS) {
                double travelY = Math.max(from.topY, to.topY);
                Box start = body(from.centerX, from.topY, from.centerZ);
                Box highStart = body(from.centerX, travelY, from.centerZ);
                Box highEnd = body(to.centerX, travelY, to.centerZ);
                if (clear(start.union(highStart), probe) && clear(highStart.union(highEnd), probe)
                    && clear(highEnd.union(body(to.centerX, to.topY, to.centerZ)), probe)) {
                    boolean supported = true;
                    for (int i = 0; i <= 8; i++) {
                        double t = i / 8.0;
                        if (supports(lerp(from.centerX, to.centerX, t), travelY,
                            lerp(from.centerZ, to.centerZ, t), STEP_HEIGHT + EPS, probe).isEmpty()) {
                            supported = false;
                            break;
                        }
                    }
                    if (supported) return true;
                }
            }
            // One normal jump, no midair turn or second jump. Start moving horizontally
            // on the rising arc; this allows jumping onto the block immediately ahead.
            double end = (1 + Math.sqrt(Math.max(0, 1 - delta / JUMP_HEIGHT))) * .5;
            double startMove = delta > 0 ? (1 - Math.sqrt(Math.max(0, 1 - delta / JUMP_HEIGHT))) * .5 : 0;
            if (end - startMove < .08) return false;
            Box previous = body(from.centerX, from.topY, from.centerZ);
            for (int i = 1; i <= 24; i++) {
                double u = end * i / 24;
                double horizontal = Math.max(0, Math.min(1, (u - startMove) / Math.max(.001, end - startMove)));
                double jumpY = from.topY + 4 * JUMP_HEIGHT * u * (1 - u);
                Box next = body(lerp(from.centerX, to.centerX, horizontal), jumpY,
                    lerp(from.centerZ, to.centerZ, horizontal));
                if (!clear(previous.union(next), probe)) return false;
                previous = next;
            }
            return true;
        }

        private List<Double> tops(int x, int z, double low, double high, Probe probe) {
            Set<Double> found = new LinkedHashSet<>();
            for (int y = floor(low) - 1; y <= floor(high); y++) {
                Cell cell = probe.cell(x, y, z);
                if (!cell.loaded) continue;
                for (Box box : cell.supportBoxes) {
                    double top = box.maxY;
                    if (top >= low - EPS && top <= high + EPS && box.maxX > x + EPS
                        && box.minX < x + 1 - EPS && box.maxZ > z + EPS && box.minZ < z + 1 - EPS)
                        found.add(top);
                    if (found.size() >= 24) return new ArrayList<>(found);
                }
            }
            return new ArrayList<>(found);
        }

        private List<Surface> supports(double x, double feetY, double z, double allowedDrop, Probe probe) {
            ArrayList<Surface> found = new ArrayList<>();
            double minX = x - halfWidth + EPS, maxX = x + halfWidth - EPS;
            double minZ = z - halfWidth + EPS, maxZ = z + halfWidth - EPS;
            for (int bx = floor(minX); bx <= floor(maxX); bx++)
                for (int bz = floor(minZ); bz <= floor(maxZ); bz++)
                    for (int by = floor(feetY - allowedDrop) - 1; by <= floor(feetY); by++) {
                        Cell cell = probe.cell(bx, by, bz);
                        if (!cell.loaded) continue;
                        for (Box box : cell.supportBoxes)
                            if (box.maxY <= feetY + EPS && box.maxY >= feetY - allowedDrop - EPS
                                && box.maxX > minX + EPS && box.minX < maxX - EPS
                                && box.maxZ > minZ + EPS && box.minZ < maxZ - EPS)
                                found.add(new Surface(bx, floor(box.maxY - .015), bz, box.maxY));
                    }
            return found;
        }

        private Box body(double x, double y, double z) {
            return new Box(x - halfWidth + EPS, y + EPS, z - halfWidth + EPS,
                x + halfWidth - EPS, y + height - EPS, z + halfWidth - EPS);
        }

        private boolean clear(Box bounds, Probe probe) {
            for (int x = floor(bounds.minX); x <= floor(bounds.maxX); x++)
                for (int z = floor(bounds.minZ); z <= floor(bounds.maxZ); z++)
                    // Fences and walls can extend above their owning block.
                    for (int y = floor(bounds.minY) - 1; y <= floor(bounds.maxY); y++) {
                        Cell cell = probe.cell(x, y, z);
                        if (!cell.loaded) return false;
                        for (Box box : cell.boxes) if (box.intersects(bounds)) return false;
                    }
            return true;
        }
    }

    private static int floor(double value) { return (int) Math.floor(value); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
