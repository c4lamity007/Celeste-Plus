package dev.c4lam.celeste.module.impl.visual.suspicious;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;

/** Bounded geometry analysis of immutable, already received terrain. No game/world access. */
public final class SuspiciousGeometry {
    public static final int SHAFT_MIN_DEPTH = 24;
    public static final int SHAFT_FLOOR_MARGIN = 8;
    public static final double SHAFT_DEPTH_FRACTION = .80;
    public static final double SHAFT_NARROW_FRACTION = .80;
    public static final int SHAFT_VISIT_LIMIT = 4096;
    public static final int FILLED_VISIT_LIMIT = 1024;
    public static final int FILLED_MIN_BLOCKS = 6;
    private static final int[][] SIDES = {{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
    private static final int[][] HORIZONTAL = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
    private static final int NARROW = 1, LANDING = 2, BROAD = 3, UNKNOWN_SECTION = 4;

    private SuspiciousGeometry() { }

    /**
     * One work step examines one surface column or route node. A step may inspect bounded
     * neighboring cells; maxWorkSteps counts these units, not raw cell() calls. The deadline
     * is checked before every unit. All candidates share this budget; each shaft also has
     * its own 4,096-node cap. An unfinished or exhausted candidate contributes nothing.
     */
    public static final class Job {
        private final Terrain terrain;
        private final int minX, maxX, minZ, maxZ;
        private final List<Pos> filledCandidates = new ArrayList<>();
        private final List<Finding> findings = new ArrayList<>();
        private List<Pos> buriedBlocks = List.of();
        private final Set<Pos> filledVisited = new HashSet<>();
        private final Set<Pos> shaftMouths = new HashSet<>();
        private final Map<Pos, Boolean> anomalies = new HashMap<>();
        private int surfaceCursor, filledCursor;
        private Shaft shaft;
        private FilledRoute filled;
        private boolean openFound, buriedFound, done;
        private long workSteps;

        public Job(Terrain terrain, int centerChunkX, int centerChunkZ, List<ChunkSummary> summaries) {
            this(terrain, centerChunkX, centerChunkZ, summaries, Set.of(Check.OPEN_SHAFTS, Check.BURIED_ACCESS_PATH));
        }

        public Job(Terrain terrain, int centerChunkX, int centerChunkZ, List<ChunkSummary> summaries, Set<Check> enabled) {
            if (terrain == null) throw new IllegalArgumentException("Terrain is required");
            this.terrain = terrain;
            minX = (centerChunkX - 1) * 16;
            maxX = minX + 48;
            minZ = (centerChunkZ - 1) * 16;
            maxZ = minZ + 48;
            openFound = !enabled.contains(Check.OPEN_SHAFTS);
            buriedFound = !enabled.contains(Check.BURIED_ACCESS_PATH);
            Set<Pos> candidates = new HashSet<>();
            if (!buriedFound && summaries != null) for (ChunkSummary summary : summaries) {
                interrupted();
                if (summary == null || Math.abs((long) summary.chunkX() - centerChunkX) > 1
                        || Math.abs((long) summary.chunkZ() - centerChunkZ) > 1) continue;
                for (Pos p : summary.candidates()) if (inside(p) && candidateKind(at(p))) candidates.add(p);
            }
            filledCandidates.addAll(candidates);
            filledCandidates.sort((a,b) -> {
                int byY = Integer.compare(b.y(), a.y());
                if (byY != 0) return byY;
                int byX = Integer.compare(a.x(), b.x());
                return byX != 0 ? byX : Integer.compare(a.z(), b.z());
            });
            done = openFound && buriedFound || terrain.topY() <= terrain.floorY();
        }

        public int advance(int maxWorkSteps, long deadlineNanos) {
            int used = 0;
            try {
                while (!done && used < Math.max(0, maxWorkSteps) && System.nanoTime() < deadlineNanos) {
                    interrupted();
                    if (!openFound && shaft != null) {
                        shaft.step();
                        if (shaft.finished) {
                            if (shaft.qualified) {
                                add(Check.OPEN_SHAFTS, shaft.mouth);
                                openFound = true;
                            }
                            shaft = null;
                        }
                    } else if (!openFound && surfaceCursor < 48 * 48) {
                        int x = minX + surfaceCursor % 48;
                        int z = minZ + surfaceCursor / 48;
                        surfaceCursor++;
                        Pos mouth = findMouth(x, z);
                        if (mouth != null && shaftMouths.add(mouth)) shaft = new Shaft(mouth);
                    } else if (!buriedFound && filled != null) {
                        filled.step();
                        if (filled.finished) {
                            if (filled.exhausted) {
                                // Remaining seeds may be the unvisited tail of this component.
                                // Treat the window as inconclusive rather than qualify a fragment.
                                filledCursor = filledCandidates.size();
                            } else if (filled.qualified()) {
                                add(Check.BURIED_ACCESS_PATH, filled.origin);
                                // Only the completed, qualifying connected route is publishable.
                                buriedBlocks = List.copyOf(new LinkedHashSet<>(filled.cells));
                                buriedFound = true;
                            }
                            filled = null;
                        }
                    } else if (!buriedFound && filledCursor < filledCandidates.size()) {
                        Pos candidate = filledCandidates.get(filledCursor++);
                        if (!filledVisited.contains(candidate) && anomalous(candidate)) filled = new FilledRoute(candidate);
                    } else done = true;
                    used++;
                    workSteps++;
                }
            } catch (CancellationException cancelled) {
                buriedBlocks = List.of();
                findings.clear();
                done = true;
                throw cancelled;
            }
            return used;
        }

        public boolean isDone() { return done; }
        public List<Finding> findings() { return List.copyOf(findings); }
        /**
         * Actual filler cells of the first qualifying route in this captured terrain, at
         * most FILLED_VISIT_LIMIT. Empty until completion or after cancellation. Consumers
         * must invalidate changed input chunks and revalidate live states before rendering.
         */
        public List<Pos> buriedBlocks() { return done ? buriedBlocks : List.of(); }
        public long workSteps() { return workSteps; }

        private void add(Check check, Pos p) {
            long id = 0xcbf29ce484222325L;
            id = (id ^ p.x()) * 0x100000001b3L;
            id = (id ^ p.y()) * 0x100000001b3L;
            id = (id ^ p.z()) * 0x100000001b3L;
            findings.add(new Finding(check, id, p.x() >> 4, p.z() >> 4));
        }

        private boolean inside(Pos p) {
            return p.x() >= minX && p.x() < maxX && p.z() >= minZ && p.z() < maxZ
                    && p.y() >= terrain.floorY() && p.y() < terrain.topY();
        }

        private int at(Pos p) { return inside(p) ? kind(terrain.cell(p.x(),p.y(),p.z())) : UNKNOWN; }

        private int surface(int x, int z) {
            return x >= minX && x < maxX && z >= minZ && z < maxZ
                    ? terrain.surfaceY(x,z) : Integer.MIN_VALUE;
        }

        private Pos findMouth(int x, int z) {
            int ownSurface = surface(x,z);
            if (ownSurface == Integer.MIN_VALUE) return null;
            // The ring lies outside a 3x1 opening. Its median measures the surrounding
            // ground instead of mistaking the hole's bottom heightmap for its entrance.
            int[] ring = new int[16];
            int count = 0;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                int h = surface(x + dx,z + dz);
                if (h == Integer.MIN_VALUE) return null;
                ring[count++] = h;
            }
            Arrays.sort(ring);
            int rim = ring[count / 2];
            if (rim - terrain.floorY() < SHAFT_MIN_DEPTH || ownSurface >= rim - 1
                    || rim >= terrain.topY() - 1) return null;
            int consistent = 0;
            for (int h : ring) if (Math.abs(h - rim) <= 2) consistent++;
            if (consistent < 12) return null; // Cliff faces and large open ravines are not shafts.
            Pos mouth = new Pos(x,rim,z);
            if (at(mouth) != AIR || at(move(mouth,0,1,0)) != AIR) return null;
            int walls = 0;
            for (int[] d : HORIZONTAL) {
                int block = at(move(mouth,d));
                if (block == UNKNOWN || fluidOrStructure(block)) return null;
                if (solidGround(block)) walls++;
            }
            return walls >= 2 ? mouth : null;
        }

        private boolean anomalous(Pos p) {
            Boolean known = anomalies.get(p);
            if (known != null) return known;
            int block = at(p);
            if (!candidateKind(block)) return false;
            int naturalNeighbors = 0, closeNatural = 0;
            boolean usable = true;
            for (int[] d : SIDES) for (int distance = 1; distance <= 2; distance++) {
                int neighbor = at(move(p,d[0] * distance,d[1] * distance,d[2] * distance));
                if (neighbor == UNKNOWN || fluidOrStructure(neighbor)) usable = false;
                if (naturalRock(neighbor)) {
                    naturalNeighbors++;
                    if (distance == 1) closeNatural++;
                }
            }
            // Dirt/coarse-soil mixing near the surface is not evidence of a filled tunnel.
            // Repeated cobble or soil must be embedded in actual natural rock.
            boolean result = usable && closeNatural >= 2 && naturalNeighbors >= 5;
            anomalies.put(p,result);
            return result;
        }

        private final class Shaft {
            final Pos mouth;
            final ArrayDeque<AirNode> queue = new ArrayDeque<>();
            final Set<Pos> visited = new HashSet<>();
            final Map<Pos,Integer> sections = new HashMap<>();
            int inspected;
            boolean finished, qualified;

            Shaft(Pos mouth) {
                this.mouth = mouth;
                queue.add(new AirNode(mouth,mouth.y(),0,0,0,0));
                visited.add(mouth);
            }

            void step() {
                if (queue.isEmpty() || inspected >= SHAFT_VISIT_LIMIT) {
                    finished = true;
                    return;
                }
                AirNode node = queue.removeFirst();
                inspected++;
                int section = section(node.pos);
                if (section == UNKNOWN_SECTION || section == BROAD) {
                    if (queue.isEmpty()) finished = true;
                    return;
                }
                int levels = node.levels;
                int narrowLevels = node.narrowLevels;
                int lowest = node.lowestY;
                int broadRun = node.broadRun;
                if (node.pos.y() < lowest || levels == 0) {
                    levels++;
                    if (section == NARROW) narrowLevels++;
                    broadRun = section == NARROW ? 0 : broadRun + 1;
                    lowest = Math.min(lowest,node.pos.y());
                }
                if (broadRun > 2 || node.horizontalRun > 8) return;
                int depth = mouth.y() - node.pos.y();
                if (depth >= SHAFT_MIN_DEPTH && node.pos.y() <= terrain.floorY() + SHAFT_FLOOR_MARGIN
                        && depth >= (mouth.y() - terrain.floorY()) * SHAFT_DEPTH_FRACTION
                        && narrowLevels >= levels * SHAFT_NARROW_FRACTION) {
                    qualified = true;
                    finished = true;
                    return;
                }
                for (int[] d : SIDES) {
                    Pos next = move(node.pos,d);
                    if (next.y() > mouth.y() || next.y() > lowest + 2 || !inside(next)
                            || visited.contains(next) || at(next) != AIR) continue;
                    // A nontrivial queue left at the cap is inconclusive, never a deduction.
                    if (visited.size() >= SHAFT_VISIT_LIMIT) {
                        finished = true;
                        qualified = false;
                        queue.clear();
                        return;
                    }
                    visited.add(next);
                    int horizontalRun = d[1] == 0 ? node.horizontalRun + 1 : 0;
                    queue.addLast(new AirNode(next,lowest,levels,narrowLevels,broadRun,horizontalRun));
                }
                if (queue.isEmpty()) finished = true;
            }

            private int section(Pos start) {
                Integer cached = sections.get(start);
                if (cached != null) return cached;
                ArrayDeque<Pos> pending = new ArrayDeque<>();
                Set<Pos> cells = new HashSet<>();
                cells.add(start);
                pending.add(start);
                int lowX=start.x(),highX=start.x(),lowZ=start.z(),highZ=start.z();
                int result = NARROW;
                while (!pending.isEmpty()) {
                    Pos p = pending.removeFirst();
                    for (int[] d : HORIZONTAL) {
                        Pos q = move(p,d);
                        int block = at(q);
                        if (block == UNKNOWN) { result = UNKNOWN_SECTION; pending.clear(); break; }
                        if (fluidOrStructure(block)) { result = BROAD; pending.clear(); break; }
                        if (block != AIR || cells.contains(q)) continue;
                        cells.add(q);
                        lowX=Math.min(lowX,q.x());highX=Math.max(highX,q.x());
                        lowZ=Math.min(lowZ,q.z());highZ=Math.max(highZ,q.z());
                        if (cells.size()>9 || highX-lowX>2 || highZ-lowZ>2) {
                            result=BROAD;pending.clear();break;
                        }
                        pending.addLast(q);
                    }
                }
                if (result==NARROW && (cells.size()>3 || highX!=lowX && highZ!=lowZ)) result=LANDING;
                for (Pos p:cells) sections.put(p,result);
                return result;
            }
        }

        private final class FilledRoute {
            final Pos origin;
            final ArrayDeque<Pos> queue = new ArrayDeque<>();
            final List<Pos> cells = new ArrayList<>();
            boolean finished, exhausted;
            int lowX,highX,lowY,highY,lowZ,highZ;

            FilledRoute(Pos origin) {
                this.origin=origin;
                lowX=highX=origin.x();lowY=highY=origin.y();lowZ=highZ=origin.z();
                queue.add(origin);
                filledVisited.add(origin);
            }

            void step() {
                if (queue.isEmpty()) {finished=true;return;}
                if (cells.size()>=FILLED_VISIT_LIMIT) {exhausted=true;finished=true;return;}
                Pos p=queue.removeFirst();
                cells.add(p);
                lowX=Math.min(lowX,p.x());highX=Math.max(highX,p.x());
                lowY=Math.min(lowY,p.y());highY=Math.max(highY,p.y());
                lowZ=Math.min(lowZ,p.z());highZ=Math.max(highZ,p.z());
                for(int[] d:SIDES) {
                    Pos q=move(p,d);
                    if(!filledVisited.contains(q) && anomalous(q)) {
                        filledVisited.add(q);queue.addLast(q);
                    }
                }
                if(queue.isEmpty())finished=true;
            }

            boolean qualified() {
                if(!finished || exhausted || cells.size()<FILLED_MIN_BLOCKS)return false;
                int dx=highX-lowX,dy=highY-lowY,dz=highZ-lowZ;
                boolean shaft=dy>=5 && dx<=2 && dz<=2;
                boolean tunnel=Math.max(dx,dz)>=5 && Math.min(dx,dz)<=2 && dy<=3;
                int horizontal=Math.max(dx,dz);
                boolean stairs=dy>=4 && horizontal>=4 && Math.min(dx,dz)<=2
                        && Math.abs(horizontal-dy)<=Math.max(3,dy/2);
                if(!shaft && !tunnel && !stairs)return false;
                // A natural patch's surface can form an elongated boundary. Demand a
                // sparse, passage-sized cross section along the primary route axis.
                Map<Integer,Integer> slices=new HashMap<>();
                for(Pos p:cells) {
                    int slice=shaft?p.y():dx>=dz?p.x():p.z();
                    if(slices.merge(slice,1,Integer::sum)>6)return false;
                }
                return slices.size()>=5;
            }
        }
    }

    private record AirNode(Pos pos,int lowestY,int levels,int narrowLevels,int broadRun,int horizontalRun) { }
    private static Pos move(Pos p,int[] d) {return move(p,d[0],d[1],d[2]);}
    private static Pos move(Pos p,int x,int y,int z) {return new Pos(p.x()+x,p.y()+y,p.z()+z);}
    private static boolean candidateKind(int kind) {
        return kind==COBBLE || kind==COBBLED_DEEPSLATE || kind==DIRT || kind==HORIZONTAL_DEEPSLATE;
    }
    private static void interrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Geometry scan cancelled");
    }
    private static boolean naturalRock(int kind) {return kind==STONE || kind==DEEPSLATE;}
    private static boolean fluidOrStructure(int kind) {
        return kind==WATER || kind==FLOWING_WATER || kind==LAVA || kind==WATERLOGGED_OTHER || kind==STRUCTURE;
    }
    private static boolean solidGround(int kind) {
        return kind==STONE || kind==DEEPSLATE || kind==HORIZONTAL_DEEPSLATE || kind==DIRT
                || kind==COARSE_DIRT || kind==ROOTED_DIRT || kind==PODZOL || kind==MYCELIUM
                || kind==COBBLE || kind==COBBLED_DEEPSLATE || kind==OTHER;
    }
}
