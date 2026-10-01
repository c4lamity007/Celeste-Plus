package dev.c4lam.celeste.module.impl.visual;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Incremental index of selected blocks in chunks already held by the client.
 *
 * <p>Only new/replaced/invalidated sections are palette-tested or collected.
 * Discovery continues with cheap loaded/identity checks so an unavailable
 * chunk can appear later without requesting it from the server. Matching
 * sections retain every local match in a 4,096-bit mask; draw limits never
 * discard index data. All methods are called on the owning client thread.</p>
 */
public final class BlockEspScan {
    public static final int DEFAULT_RADIUS = 64;
    public static final int MIN_RADIUS = 8;
    public static final int MAX_RADIUS = 512;
    public static final int MAX_CHUNK_RADIUS = 35;
    public static final int MAX_SECTION_STATES = 131072;
    public static final int PALETTE_BUDGET = 96;
    public static final int MATCHED_SECTION_BUDGET = 2;
    public static final int INDEXED_SECTION_BUDGET = 16;
    public static final int INDEX_RECHECK_BUDGET = INDEXED_SECTION_BUDGET + MATCHED_SECTION_BUDGET;
    public static final int DISCOVERY_BUDGET = 512;
    public static final int REORDER_BUDGET = 256;
    public static final int RENDER_CANDIDATE_LIMIT = 1024;
    public static final int DRAW_LIMIT = 512;
    /** Compatibility limit for snapshot(); the data index has no point cap. */
    public static final int CACHE_LIMIT = RENDER_CANDIDATE_LIMIT;

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
        /** The implementation must never request a missing chunk. */
        default boolean chunkLoaded(int chunkX, int chunkZ) { return true; }
        boolean loaded(Section section);
        /** Stable section identity, compared by reference; null disables this fallback. */
        default Object revision(Section section) { return null; }
        /** Cheap palette-level test, performed only when this section is dirty. */
        boolean mayMatch(Section section);
        default boolean indexed(Section section) { return false; }
        void collect(Section section, PointConsumer output);
        /** Legacy adapter hook. The index uses invalidations instead of per-point polling. */
        default boolean matches(Point point) { return true; }
    }

    /** Immutable section mask. Local index layout is (localY << 8) | (localZ << 4) | localX. */
    public static final class SectionSnapshot {
        private final Section section;
        private final long[] words;
        private final int size;

        private SectionSnapshot(Section section, long[] words, int size) {
            this.section = section;
            this.words = words;
            this.size = size;
        }
        public Section section() { return section; }
        public int size() { return size; }
        /** Start with -1. Returns -1 after the final match. Does not allocate Points. */
        public int nextLocalIndex(int previousIndex) {
            int next = previousIndex + 1;
            if (next < 0 || next >= 4096) return -1;
            int word = next >>> 6;
            if (word >= words.length) return -1;
            long remaining = words[word] & (-1L << (next & 63));
            while (remaining == 0) {
                if (++word >= words.length) return -1;
                remaining = words[word];
            }
            return (word << 6) + Long.numberOfTrailingZeros(remaining);
        }
        public Point point(int localIndex) {
            if (localIndex < 0 || localIndex >= 4096) throw new IndexOutOfBoundsException(localIndex);
            return new Point(section.x * 16 + (localIndex & 15),
                section.y * 16 + (localIndex >>> 8), section.z * 16 + ((localIndex >>> 4) & 15));
        }
    }

    private static final class State {
        final Section section;
        Object revision;
        boolean needsPalette = true;
        boolean possible;
        boolean indexed;
        boolean queued;
        SectionSnapshot snapshot;
        State(Section section, Object revision) { this.section = section; this.revision = revision; }
    }

    private static final class Chunk {
        final int x, z;
        final State[] sections;
        Chunk(int x, int z, int count) { this.x = x; this.z = z; sections = new State[count]; }
    }

    private final LinkedHashMap<Long, Chunk> chunks = new LinkedHashMap<>();
    private final LinkedHashMap<Section, State> pending = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Chunk> maintenance = new LinkedHashMap<>();
    private final LinkedHashMap<Long, Discovery> loadedEvents = new LinkedHashMap<>();
    private Object world;
    private Point center;
    private int radius, chunkRadius, bottomSection, topSection;
    private Discovery discovery;
    private Discovery nearby;
    private int nearX = Integer.MIN_VALUE, nearY = Integer.MIN_VALUE, nearZ = Integer.MIN_VALUE;
    private Node ordered;
    private Point orderCenter;
    private Iterator<SectionSnapshot> reorderSource;
    private Node reordered;
    private Point reorderCenter;
    private List<SectionSnapshot> published = List.of();

    public void clear() {
        chunks.clear();
        pending.clear();
        maintenance.clear();
        loadedEvents.clear();
        world = null;
        center = orderCenter = reorderCenter = null;
        discovery = nearby = null;
        ordered = reordered = null;
        reorderSource = null;
        published = List.of();
        nearX = nearY = nearZ = Integer.MIN_VALUE;
        radius = chunkRadius = bottomSection = topSection = 0;
    }

    /**
     * Nearer sections first, with ordering refreshed incrementally after movement.
     * Both the list and its bit masks remain valid after later ticks/invalidation.
     * Iteration is O(sections visited); publication does not copy/sort all matches.
     */
    public List<SectionSnapshot> snapshotSections() { return published; }

    /**
     * Legacy diagnostic view only. The renderer should use snapshotSections(),
     * cull sections, then choose its own bounded set of visible points.
     */
    public List<Point> snapshot() {
        if (center == null) return List.of();
        Comparator<Point> nearest = Comparator.comparingLong((Point p) -> p.distance(center))
            .thenComparingInt(Point::x).thenComparingInt(Point::y).thenComparingInt(Point::z);
        PriorityQueue<Point> farthest = new PriorityQueue<>(CACHE_LIMIT, nearest.reversed());
        long distanceLimit = radius == 0 ? Long.MAX_VALUE : (long) radius * radius;
        for (SectionSnapshot section : published) {
            for (int i = section.nextLocalIndex(-1); i >= 0; i = section.nextLocalIndex(i)) {
                Point point = section.point(i);
                if (point.distance(center) > distanceLimit) continue;
                if (farthest.size() < CACHE_LIMIT) farthest.add(point);
                else if (nearest.compare(point, farthest.peek()) < 0) {
                    farthest.remove();
                    farthest.add(point);
                }
            }
        }
        ArrayList<Point> points = new ArrayList<>(farthest);
        points.sort(nearest);
        return List.copyOf(points);
    }

    /** Manual spherical range. The top section argument is exclusive. */
    public void tick(Object identity, Point center, int requestedRadius, int requestedBottomSection,
                     int requestedTopSection, Probe probe) {
        int range = clampRadius(requestedRadius);
        tick(identity, center, (range + 15) / 16, range, requestedBottomSection, requestedTopSection, probe);
    }

    /**
     * A zero manualRadius indexes the loaded chunk square through the entire
     * vertical world. Nonzero manualRadius restricts discovery to that sphere;
     * masks still retain complete sections so a small move does not rescan them.
     */
    public void tick(Object identity, Point center, int discoveryChunkRadius, int manualRadius,
                     int requestedBottomSection, int requestedTopSection, Probe probe) {
        if (identity == null || center == null || probe == null || requestedTopSection <= requestedBottomSection
            || (long) requestedTopSection - requestedBottomSection > 4096) {
            clear();
            return;
        }
        int nextChunkRadius = Math.max(0, Math.min(MAX_CHUNK_RADIUS, discoveryChunkRadius));
        // Normal 384-block worlds fit all 71x71 chunks. Extremely tall custom
        // worlds reduce the square radius instead of allocating unbounded masks
        // or repeatedly evicting/re-reading data inside a stationary window.
        int maximumWidth = (int) Math.sqrt(MAX_SECTION_STATES / (requestedTopSection - requestedBottomSection));
        nextChunkRadius = Math.min(nextChunkRadius, Math.max(0, (maximumWidth - 1) / 2));
        int nextRadius = manualRadius == 0 ? 0 : clampRadius(manualRadius);
        boolean reset = world != identity || bottomSection != requestedBottomSection || topSection != requestedTopSection;
        if (reset) clear();
        boolean changedRange = chunkRadius != nextChunkRadius || radius != nextRadius;
        world = identity;
        this.center = center;
        chunkRadius = nextChunkRadius;
        radius = nextRadius;
        bottomSection = requestedBottomSection;
        topSection = requestedTopSection;
        if (orderCenter == null) orderCenter = center;
        if (discovery == null || changedRange) discovery = new Discovery(center, chunkRadius);
        int cx = Math.floorDiv(center.x, 16), cy = Math.floorDiv(center.y, 16), cz = Math.floorDiv(center.z, 16);
        if (cx != nearX || cy != nearY || cz != nearZ) {
            nearby = new Discovery(center, Math.min(1, chunkRadius));
            nearX = cx; nearY = cy; nearZ = cz;
        }

        Budget budget = new Budget();
        maintainChunks(probe);
        drainPending(probe, budget, 128);
        if (!loadedEvents.isEmpty()) {
            Map.Entry<Long, Discovery> event = loadedEvents.entrySet().iterator().next();
            discover(event.getValue(), probe, budget, 64);
            if (event.getValue().done()) loadedEvents.remove(event.getKey());
        }
        // A small near pass responds to crossing a chunk seam. The main cursor
        // keeps progressing, so repeatedly crossing the seam cannot starve it.
        if (nearby != null) {
            discover(nearby, probe, budget, 64);
            if (nearby.done()) nearby = null;
        }
        discover(discovery, probe, budget, DISCOVERY_BUDGET);
        if (discovery.done()) discovery = new Discovery(center, chunkRadius);
        drainPending(probe, budget, 128);
        reorder();
        publish();
    }

    public void invalidate(Section section) {
        if (section == null) return;
        State state = state(section);
        if (state == null) { prioritizeChunk(section.x, section.z); return; }
        dirty(state);
        publish();
    }

    public void invalidateChunk(int chunkX, int chunkZ) {
        Chunk chunk = chunks.get(chunkKey(chunkX, chunkZ));
        if (chunk != null) for (State state : chunk.sections) if (state != null) dirty(state);
        // Queue one lazy chunk cursor, not world-height work, from an event hook.
        prioritizeChunk(chunkX, chunkZ);
        publish();
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        removeChunk(chunkKey(chunkX, chunkZ));
        loadedEvents.remove(chunkKey(chunkX, chunkZ));
        publish();
    }

    private void prioritizeChunk(int x, int z) {
        if (!insideChunk(x, z)) return;
        int width = chunkRadius * 2 + 1;
        if (loadedEvents.size() >= width * width) loadedEvents.pollFirstEntry();
        loadedEvents.computeIfAbsent(chunkKey(x, z), key -> new Discovery(new Point(x * 16, center.y, z * 16), 0));
    }

    private void dirty(State state) {
        replace(state, null);
        state.needsPalette = true;
        enqueue(state);
    }

    private void enqueue(State state) {
        if (!state.queued) { state.queued = true; pending.put(state.section, state); }
    }

    private State state(Section section) {
        if (section == null || section.y < bottomSection || section.y >= topSection) return null;
        Chunk chunk = chunks.get(chunkKey(section.x, section.z));
        return chunk == null ? null : chunk.sections[section.y - bottomSection];
    }

    private void discover(Discovery cursor, Probe probe, Budget budget, int allowance) {
        int visits = 0;
        while (!cursor.done() && visits < allowance && budget.discovery < DISCOVERY_BUDGET) {
            visits++;
            budget.discovery++;
            if (!insideChunk(cursor.chunkX(), cursor.chunkZ()) || !probe.chunkLoaded(cursor.chunkX(), cursor.chunkZ())) {
                removeChunk(chunkKey(cursor.chunkX(), cursor.chunkZ()));
                cursor.nextChunk();
                continue;
            }
            Section section = cursor.nextSection(bottomSection, topSection);
            if (section == null) continue;
            if (radius != 0 && sectionDistance(section, center) > (long) radius * radius) continue;
            Chunk chunk = chunks.get(chunkKey(section.x, section.z));
            if (!probe.loaded(section)) {
                if (chunk != null) removeState(chunk, section.y - bottomSection);
                continue;
            }
            if (chunk == null) chunk = addChunk(section.x, section.z);
            int index = section.y - bottomSection;
            State state = chunk.sections[index];
            Object revision = probe.revision(section);
            if (state == null) {
                state = new State(section, revision);
                chunk.sections[index] = state;
                enqueue(state);
            } else if (state.revision != revision) {
                state.revision = revision;
                dirty(state);
            }
            // Discovery itself performs no palette/4096-state walk. The shared
            // work queue enforces those separate budgets below.
        }
    }

    private void drainPending(Probe probe, Budget budget, int allowance) {
        int attempts = Math.min(allowance, pending.size());
        ArrayDeque<State> deferred = new ArrayDeque<>();
        for (int i = 0; i < attempts; i++) {
            if (pending.isEmpty()) break;
            State current = pending.pollFirstEntry().getValue();
            current.queued = false;
            if (state(current.section) != current) continue;
            if (!insideChunk(current.section.x, current.section.z)) {
                removeChunk(chunkKey(current.section.x, current.section.z));
                continue;
            }
            if (!probe.loaded(current.section)) {
                Chunk chunk = chunks.get(chunkKey(current.section.x, current.section.z));
                if (chunk != null) removeState(chunk, current.section.y - bottomSection);
                continue;
            }
            Object revision = probe.revision(current.section);
            boolean classifiedNow = false;
            if (current.revision != revision) {
                current.revision = revision;
                replace(current, null);
                current.needsPalette = true;
            }
            if (current.needsPalette) {
                if (budget.palettes >= PALETTE_BUDGET) { current.queued = true; deferred.addLast(current); continue; }
                budget.palettes++;
                current.possible = probe.mayMatch(current.section);
                current.indexed = current.possible && probe.indexed(current.section);
                current.needsPalette = false;
                classifiedNow = true;
            }
            if (!current.possible) { replace(current, null); continue; }
            boolean indexed = current.indexed;
            if (indexed ? budget.indexed >= INDEXED_SECTION_BUDGET : budget.matched >= MATCHED_SECTION_BUDGET) {
                current.queued = true;
                deferred.addLast(current);
                continue;
            }
            // Pending block-entity data can change independently of section
            // identity. Recheck only a collection-ready entry, with a separate
            // small bound, before deciding which collection budget to charge.
            if (!classifiedNow) {
                if (budget.indexRechecks >= INDEX_RECHECK_BUDGET) {
                    current.queued = true;
                    deferred.addLast(current);
                    continue;
                }
                budget.indexRechecks++;
                indexed = current.indexed = probe.indexed(current.section);
                if (indexed ? budget.indexed >= INDEXED_SECTION_BUDGET : budget.matched >= MATCHED_SECTION_BUDGET) {
                    current.queued = true;
                    deferred.addLast(current);
                    continue;
                }
            }
            if (indexed) budget.indexed++; else budget.matched++;
            long[] words = new long[64];
            Section section = current.section;
            probe.collect(section, point -> {
                if (point == null || !section.contains(point)) return;
                int local = ((point.y & 15) << 8) | ((point.z & 15) << 4) | (point.x & 15);
                words[local >>> 6] |= 1L << (local & 63);
            });
            int count = 0, length = words.length;
            while (length > 0 && words[length - 1] == 0) length--;
            for (int word = 0; word < length; word++) count += Long.bitCount(words[word]);
            replace(current, count == 0 ? null : new SectionSnapshot(section, Arrays.copyOf(words, length), count));
        }
        // Keep deferred near-first work ahead of newly discovered distant work;
        // otherwise a dense radius can rotate its nearest masks to the back.
        while (!deferred.isEmpty()) {
            State current = deferred.removeLast();
            if (state(current.section) == current) pending.putFirst(current.section, current);
            else current.queued = false;
        }
    }

    private static final class Budget { int discovery, palettes, matched, indexed, indexRechecks; }

    private Chunk addChunk(int x, int z) {
        // The hard bound is on cached chunks, not matches. A moving observer
        // cannot retain an unlimited trail of old section masks.
        int width = chunkRadius * 2 + 1;
        int capacity = width * width;
        if (chunks.size() >= capacity) removeChunk(chunks.keySet().iterator().next());
        Chunk chunk = new Chunk(x, z, topSection - bottomSection);
        chunks.put(chunkKey(x, z), chunk);
        maintenance.put(chunkKey(x, z), chunk);
        return chunk;
    }

    private void maintainChunks(Probe probe) {
        int checks = Math.min(8, maintenance.size());
        for (int i = 0; i < checks; i++) {
            Chunk chunk = maintenance.pollFirstEntry().getValue();
            if (chunks.get(chunkKey(chunk.x, chunk.z)) != chunk) continue;
            if (!insideChunk(chunk.x, chunk.z) || !probe.chunkLoaded(chunk.x, chunk.z)) removeChunk(chunkKey(chunk.x, chunk.z));
            else maintenance.put(chunkKey(chunk.x, chunk.z), chunk);
        }
    }

    private boolean insideChunk(int x, int z) {
        return center != null && Math.abs((long) x - Math.floorDiv(center.x, 16)) <= chunkRadius
            && Math.abs((long) z - Math.floorDiv(center.z, 16)) <= chunkRadius;
    }

    private void removeChunk(long key) {
        Chunk chunk = chunks.remove(key);
        maintenance.remove(key);
        if (chunk != null) for (int i = 0; i < chunk.sections.length; i++) removeState(chunk, i);
    }

    private void removeState(Chunk chunk, int index) {
        State state = chunk.sections[index];
        if (state != null) {
            pending.remove(state.section);
            state.queued = false;
            replace(state, null);
        }
        chunk.sections[index] = null;
    }

    private void replace(State state, SectionSnapshot next) {
        if (state.snapshot == next) return;
        if (state.snapshot != null) {
            ordered = remove(ordered, state.section, orderCenter);
            if (reorderSource != null) reordered = remove(reordered, state.section, reorderCenter);
        }
        state.snapshot = next;
        if (next != null) {
            ordered = insert(ordered, next, orderCenter);
            if (reorderSource != null) reordered = insert(reordered, next, reorderCenter);
        }
    }

    private void reorder() {
        if (ordered == null) { orderCenter = center; reordered = null; reorderSource = null; return; }
        if (reorderSource == null && orderCenter.distance(center) > 64) {
            reorderCenter = center;
            reorderSource = new TreeIterator(ordered);
            reordered = null;
        }
        if (reorderSource == null) return;
        for (int i = 0; i < REORDER_BUDGET && reorderSource.hasNext(); i++) {
            SectionSnapshot candidate = reorderSource.next();
            State state = state(candidate.section);
            if (state != null && state.snapshot == candidate) reordered = insert(reordered, candidate, reorderCenter);
        }
        if (!reorderSource.hasNext()) {
            ordered = reordered;
            orderCenter = reorderCenter;
            reorderSource = null;
            reordered = null;
        }
    }

    private void publish() {
        if (ordered == null) published = List.of();
        else if (!(published instanceof SectionList list) || list.root != ordered) published = new SectionList(ordered);
    }

    /** Constant-space square spiral with section Y alternating around the initial observer. */
    private static final class Discovery {
        final int centerX, centerY, centerZ, radius;
        int x, z, direction, leg = 1, legRemaining = 1, completedLegs, visitedChunks;
        int sectionCursor;
        Discovery(Point center, int radius) {
            centerX = Math.floorDiv(center.x, 16);
            centerY = Math.floorDiv(center.y, 16);
            centerZ = Math.floorDiv(center.z, 16);
            this.radius = radius;
        }
        int chunkX() { return centerX + x; }
        int chunkZ() { return centerZ + z; }
        boolean done() { int width = radius * 2 + 1; return visitedChunks >= width * width; }
        Section nextSection(int bottom, int top) {
            int clamped = Math.max(bottom, Math.min(top - 1, centerY));
            int count = top - bottom;
            // Enumeration has no duplicate Ys, including a camera outside the
            // world or near one edge: visit clamped, then alternating offsets.
            while (sectionCursor < count * 2) {
                int offset = sectionCursor++;
                int y = clamped + ((offset & 1) == 1 ? (offset + 1) / 2 : -offset / 2);
                if (y < bottom || y >= top) continue;
                Section result = new Section(chunkX(), y, chunkZ());
                if (sectionCursor >= count * 2 - 1) nextChunk();
                return result;
            }
            nextChunk();
            return null;
        }
        void nextChunk() {
            visitedChunks++;
            sectionCursor = 0;
            if (done()) return;
            do {
                switch (direction) {
                    case 0 -> x++;
                    case 1 -> z++;
                    case 2 -> x--;
                    default -> z--;
                }
                if (--legRemaining == 0) {
                    direction = (direction + 1) & 3;
                    if ((++completedLegs & 1) == 0) leg++;
                    legRemaining = leg;
                }
            } while (Math.abs(x) > radius || Math.abs(z) > radius);
        }
    }

    /* Persistent AVL index: immutable snapshots without an O(matches) list copy
       each tick. Updating two section masks changes only their search paths. */
    private static final class Node {
        final SectionSnapshot value;
        final Node left, right;
        final int height, size;
        Node(SectionSnapshot value, Node left, Node right) {
            this.value = value; this.left = left; this.right = right;
            height = 1 + Math.max(height(left), height(right));
            size = 1 + size(left) + size(right);
        }
    }
    private static int height(Node node) { return node == null ? 0 : node.height; }
    private static int size(Node node) { return node == null ? 0 : node.size; }
    private static int compare(Section a, Section b, Point center) {
        int c = Long.compare(sectionDistance(a, center), sectionDistance(b, center));
        if (c == 0) c = Integer.compare(a.x, b.x);
        if (c == 0) c = Integer.compare(a.y, b.y);
        if (c == 0) c = Integer.compare(a.z, b.z);
        return c;
    }
    private static Node insert(Node node, SectionSnapshot value, Point center) {
        if (node == null) return new Node(value, null, null);
        int order = compare(value.section, node.value.section, center);
        if (order == 0) return node.value == value ? node : new Node(value, node.left, node.right);
        return balance(order < 0 ? new Node(node.value, insert(node.left, value, center), node.right)
            : new Node(node.value, node.left, insert(node.right, value, center)));
    }
    private static Node remove(Node node, Section section, Point center) {
        if (node == null) return null;
        int order = compare(section, node.value.section, center);
        if (order < 0) return balance(new Node(node.value, remove(node.left, section, center), node.right));
        if (order > 0) return balance(new Node(node.value, node.left, remove(node.right, section, center)));
        if (node.left == null) return node.right;
        if (node.right == null) return node.left;
        Node next = node.right;
        while (next.left != null) next = next.left;
        return balance(new Node(next.value, node.left, remove(node.right, next.value.section, center)));
    }
    private static Node balance(Node node) {
        int delta = height(node.left) - height(node.right);
        if (delta > 1) {
            Node left = node.left;
            if (height(left.left) < height(left.right)) left = rotateLeft(left);
            return rotateRight(new Node(node.value, left, node.right));
        }
        if (delta < -1) {
            Node right = node.right;
            if (height(right.right) < height(right.left)) right = rotateRight(right);
            return rotateLeft(new Node(node.value, node.left, right));
        }
        return node;
    }
    private static Node rotateLeft(Node node) {
        Node right = node.right;
        return new Node(right.value, new Node(node.value, node.left, right.left), right.right);
    }
    private static Node rotateRight(Node node) {
        Node left = node.left;
        return new Node(left.value, left.left, new Node(node.value, left.right, node.right));
    }
    private static final class SectionList extends AbstractList<SectionSnapshot> {
        final Node root;
        SectionList(Node root) { this.root = root; }
        @Override public int size() { return root.size; }
        @Override public SectionSnapshot get(int index) {
            if (index < 0 || index >= root.size) throw new IndexOutOfBoundsException(index);
            Node node = root;
            while (node != null) {
                int left = BlockEspScan.size(node.left);
                if (index < left) node = node.left;
                else if (index == left) return node.value;
                else { index -= left + 1; node = node.right; }
            }
            throw new AssertionError("Invalid section index");
        }
        @Override public Iterator<SectionSnapshot> iterator() { return new TreeIterator(root); }
    }
    private static final class TreeIterator implements Iterator<SectionSnapshot> {
        final ArrayDeque<Node> stack = new ArrayDeque<>();
        TreeIterator(Node root) { descend(root); }
        void descend(Node node) { while (node != null) { stack.addLast(node); node = node.left; } }
        @Override public boolean hasNext() { return !stack.isEmpty(); }
        @Override public SectionSnapshot next() {
            if (stack.isEmpty()) throw new NoSuchElementException();
            Node node = stack.removeLast();
            descend(node.right);
            return node.value;
        }
    }

    static int clampRadius(int requestedRadius) { return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, requestedRadius)); }
    static Section sectionOf(Point point) {
        return new Section(Math.floorDiv(point.x, 16), Math.floorDiv(point.y, 16), Math.floorDiv(point.z, 16));
    }
    static long sectionDistance(Section section, Point point) {
        int minX = section.x * 16, minY = section.y * 16, minZ = section.z * 16;
        long dx = axisDistance(point.x, minX, minX + 15), dy = axisDistance(point.y, minY, minY + 15),
            dz = axisDistance(point.z, minZ, minZ + 15);
        return dx * dx + dy * dy + dz * dz;
    }
    private static long axisDistance(int value, int minimum, int maximum) {
        if (value < minimum) return (long) minimum - value;
        if (value > maximum) return (long) value - maximum;
        return 0;
    }
    private static long chunkKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
