import dev.c4lam.celeste.module.impl.visual.BlockEspScan;
import dev.c4lam.celeste.module.impl.visual.BlockEspScan.Point;
import dev.c4lam.celeste.module.impl.visual.BlockEspScan.Section;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Production scan scheduler fixtures; Minecraft render checks remain separate. */
public final class BlockEspScanCheck {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static Section section(Point p) {
        return new Section(Math.floorDiv(p.x(), 16), Math.floorDiv(p.y(), 16), Math.floorDiv(p.z(), 16));
    }
    private static final class Probe implements BlockEspScan.Probe {
        final Set<Point> points = new HashSet<>();
        final Set<Section> unloaded = new HashSet<>();
        boolean allPositive, indexed;
        int reads, collections;
        public boolean loaded(Section s) { return !unloaded.contains(s); }
        public boolean mayMatch(Section s) {
            reads++;
            return allPositive || points.stream().anyMatch(p -> section(p).equals(s));
        }
        public boolean indexed(Section s) { return indexed; }
        public void collect(Section s, BlockEspScan.PointConsumer output) {
            collections++;
            for (Point p : points) if (section(p).equals(s)) output.accept(p);
        }
        public boolean matches(Point p) { return points.contains(p); }
        void counters() { reads = collections = 0; }
    }
    public static void main(String[] args) {
        Object world = new Object();
        BlockEspScan scan = new BlockEspScan();
        Probe probe = new Probe();
        Point center = new Point(0, 8, 0);
        Point negative = new Point(-1, 8, -1), edge = new Point(64, 8, 0), outside = new Point(65, 8, 0);
        probe.points.addAll(List.of(negative, edge, outside));
        for (int tick = 0; tick < 12; tick++) scan.tick(world, center, 64, 0, 0, probe);
        require(scan.snapshot().contains(negative), "Negative chunk coordinates lost a match");
        require(scan.snapshot().contains(edge), "Range boundary excluded");
        require(!scan.snapshot().contains(outside), "Outside-range block included");
        List<Point> prior = scan.snapshot();
        probe.points.remove(negative);
        scan.tick(world, center, 64, 0, 0, probe);
        require(!scan.snapshot().contains(negative), "Removed block remained in next tick");
        require(prior.contains(negative), "Published immutable snapshot mutated");
        probe.unloaded.add(section(edge));
        scan.tick(world, center, 64, 0, 0, probe);
        require(!scan.snapshot().contains(edge), "Unloaded chunk retained stale blocks");
        probe.unloaded.clear();
        for (int tick = 0; tick < 12; tick++) scan.tick(world, center, 64, 0, 0, probe);
        require(scan.snapshot().contains(edge), "Reloaded chunk was not rediscovered");
        probe.points.clear();
        scan.tick(new Object(), center, 64, 0, 0, probe);
        require(scan.snapshot().isEmpty(), "World change retained positions");
        scan.clear();
        require(scan.snapshot().isEmpty(), "Disable/reset retained positions");

        // Continuously crossing a chunk seam must eventually visit farther
        // matching sections rather than repeating the closest two forever.
        probe = new Probe();
        probe.allPositive = true;
        Point farther = new Point(60, 8, 8);
        probe.points.add(farther);
        for (int tick = 0; tick < 96 && !scan.snapshot().contains(farther); tick++) {
            probe.counters();
            scan.tick(world, new Point((tick & 1) == 0 ? 15 : 16, 8, 8), 64, 0, 0, probe);
            require(probe.collections <= BlockEspScan.MATCHED_SECTION_BUDGET, "Dense state-walk budget exceeded");
            require(probe.reads <= BlockEspScan.PALETTE_BUDGET, "Palette budget exceeded");
        }
        require(scan.snapshot().contains(farther), "Movement starved farther blocks");

        scan.clear();
        probe.indexed = true;
        probe.counters();
        scan.tick(world, center, 64, 0, 0, probe);
        require(probe.collections == BlockEspScan.INDEXED_SECTION_BUDGET, "Storage index did not use faster budget");
        require(probe.reads <= BlockEspScan.PALETTE_BUDGET, "Indexed palette budget exceeded");

        scan.clear();
        probe = new Probe();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            probe.points.add(new Point(x, y, z));
        }
        scan.tick(world, new Point(8, 8, 8), 64, 0, 0, probe);
        require(scan.snapshot().size() == BlockEspScan.CACHE_LIMIT, "Dense cache was not capped");
        require(scan.snapshot().contains(new Point(8, 8, 8)), "Nearest block was evicted");
        long last = -1;
        for (Point p : scan.snapshot()) {
            long distance = (long) (p.x() - 8) * (p.x() - 8) + (long) (p.y() - 8) * (p.y() - 8)
                + (long) (p.z() - 8) * (p.z() - 8);
            require(distance >= last, "Published cache not ordered nearest first");
            last = distance;
        }
        scan.tick(null, center, 64, 0, 0, probe);
        require(scan.snapshot().isEmpty(), "Disconnected state retained positions");
        System.out.println("Block ESP scan: " + checks + " checks passed");
    }
}
