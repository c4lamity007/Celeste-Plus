package dev.c4lam.celeste.module.impl.visual;

import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.gui.model.CelestePage;
import dev.c4lam.celeste.gui.model.GuiModule;
import dev.c4lam.celeste.gui.model.GuiSetting;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousGeometry;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousSnapshots;
import dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousActivity;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousAnalysis.*;
import static dev.c4lam.celeste.module.impl.visual.suspicious.SuspiciousSnapshots.key;

/** Bounded client-thread capture and one cancellable worker over immutable terrain only. */
public final class SuspiciousChunkFinderFeature implements ModuleFeature {
    public static final String NAME = "Suspicious Chunk Finder";
    private static final String[] LABELS = {"Rotated deepslate", "Mismatched soil", "Grounded vines",
        "Mature geode", "Missing budding", "Surface kelp", "Mature kelp",
        "Buried paths", "Open shafts"};
    private static final int CAPTURE_BLOCKS = 8192, MAX_RESULTS = 512, MAX_RADIUS = 5;
    private static final long CAPTURE_NANOS = 1_000_000L;
    private static final int MAX_PATH_BLOCKS = 2048, PATH_PUBLICATION_VISITS = 8192;
    private static volatile SuspiciousChunkFinderFeature active;
    private final SuspiciousSnapshots.Cache cache = new SuspiciousSnapshots.Cache();
    private final Map<Long, Long> revisions = new HashMap<>();
    private final LinkedHashSet<Long> dirty = new LinkedHashSet<>(), pending = new LinkedHashSet<>(), changedChunks = new LinkedHashSet<>();
    private final LinkedHashMap<Long, Result> results = new LinkedHashMap<>();
    private final IdentityHashMap<BlockState, Classified> classified = new IdentityHashMap<>();
    private volatile List<Result> displayed = List.of();
    private volatile List<PathBlock> displayedPaths = List.of();
    private final LinkedHashMap<Long, GrowthObservation> activityEvidence = new LinkedHashMap<>();
    private final Map<Long, EnumSet<Check>> growthKinds = new HashMap<>();
    private volatile List<ActivityMarker> displayedActivity = List.of();
    private SuspiciousActivity.Result activityResult;
    private Set<Finding> activityFindings = Set.of();
    private List<SuspiciousActivity.ScoredCandidate> activityScores = List.of();
    private Future<ActivityResult> activityFuture;
    private long activityVersion;
    private int activityRadius = 7, nextEstimate, activityX, activityZ;
    private boolean showActivity = true, activityDirty, preferDirty = true, surfaceDirty;
    private boolean publicationDirty, activityMarkersDirty;
    private final Map<Long, SurfaceSample> estimateSurfaces = new HashMap<>();
    private int nextSurface;
    private ClientWorld world;
    private String session = "";
    private int floor, top, threshold = 35, tick, nextDiscovery, discoverX, discoverZ, radius;
    private List<Long> discovery = List.of();
    private boolean enabled, showReasons, resumeHistory, showPaths = true;
    private EnumSet<Check> checks = EnumSet.allOf(Check.class);
    private Capture capture;
    private ExecutorService worker;
    private Future<Result> future;
    private long generation, snapshotSerial;
    private String failure = "";

    public static GuiModule settings() {
        List<GuiSetting> settings = new ArrayList<>();
        settings.add(new GuiSetting.Slider("Flag score", "Higher scores need more evidence", 35, 5, 130, 5));
        for (String label : LABELS) settings.add(new GuiSetting.Toggle(label,
            label.equals("Open shafts") ? "Subtract 20 for a deep shaft within 3 chunks of an estimate" : "Include this evidence", true));
        settings.add(new GuiSetting.Toggle("Path blocks", "Outline the blocks in suspected buried paths", true));
        var activity = new GuiSetting.Toggle("Activity estimate", "Show possible activity locations from growth patterns", true);
        settings.add(activity);
        settings.add(new GuiSetting.Slider("Tick radius", "Estimated activity radius in chunks", 7, 2, 12, 1)
            .visibleWhen(activity::value));
        settings.add(new GuiSetting.Toggle("Show reasons", "Show the nearest estimate's score and evidence", false));
        var advanced = new GuiSetting.Toggle("Advanced", "Server scan boundary", false);
        settings.add(advanced);
        settings.add(new GuiSetting.Slider("Scan floor Y", "Lowest real block data sent by this server", 0, -64, 320, 1)
            .visibleWhen(advanced::value));
        settings.add(new GuiSetting.Keybind("Keybind", "Toggle module", -1));
        return new GuiModule(NAME, "Find signs of underground activity", CelestePage.VISUALS, false,
            settings.toArray(GuiSetting[]::new));
    }

    @Override public String name() { return NAME; }
    public void initialize() {
        BetaSubscriptions.register(ClientChunkEvents.CHUNK_LOAD, "celeste:suspicious/load",
            ClientChunkEvents.Load.class, (changedWorld, chunk) -> changed(changedWorld, chunk.getPos().x, chunk.getPos().z),
            ClientChunkEvents.CHUNK_LOAD::register);
        BetaSubscriptions.register(ClientChunkEvents.CHUNK_UNLOAD, "celeste:suspicious/unload",
            ClientChunkEvents.Unload.class, (changedWorld, chunk) -> unload(changedWorld, chunk.getPos().x, chunk.getPos().z),
            ClientChunkEvents.CHUNK_UNLOAD::register);
        BetaSubscriptions.register(HudElementRegistry.class, "celeste:suspicious/reasons", HudElement.class,
            (context, counter) -> reasons(context),
            proxy -> HudElementRegistry.addLast(Identifier.of("celeste", "suspicious_reasons"), proxy));
    }

    /** Shared startup/late-attach packet callback; called on the game thread. */
    public static void blockChanged(ClientWorld world, BlockPos pos) {
        var feature = active;
        if (feature != null && pos.getY() >= feature.floor && pos.getY() < feature.top)
            feature.changed(world, pos.getX() >> 4, pos.getZ() >> 4);
    }
    private void changed(ClientWorld changedWorld, int x, int z) {
        if (!enabled || world == null || world != changedWorld) return;
        long id = key(x, z);
        if (estimateSurfaces.remove(id) != null) surfaceDirty = true;
        // Coalesce large chunk-delta packets: no per-block rescans, allocations or render publications.
        if (Math.abs((long) x - discoverX) > radius + 1 || Math.abs((long) z - discoverZ) > radius + 1) return;
        if (changedChunks.size() < SuspiciousSnapshots.Cache.MAX_CHUNKS && changedChunks.add(id))
            revisions.put(id, revisions.getOrDefault(id, 0L) + 1L);
    }
    private void unload(ClientWorld changedWorld, int x, int z) {
        if (world != changedWorld) return;
        if (discardChunk(x, z)) publish();
    }
    private boolean discardChunk(int x, int z) {
        long id = key(x, z);
        boolean surfaceChanged = estimateSurfaces.remove(id) != null;
        if (cache.live(id) == null && !dirty.contains(id) && !changedChunks.contains(id)
            && (capture == null || capture.id != id)) return surfaceChanged;
        cache.unload(id); dirty.remove(id); changedChunks.remove(id); revisions.remove(id);
        if (capture != null && capture.id == id) capture = null;
        invalidate(x, z);
        return true;
    }
    private void invalidate(int x, int z) {
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            long center = key(x + dx, z + dz);
            results.remove(center);
            if (cache.live(center) != null && pending.size() < MAX_RESULTS) pending.add(center);
        }
    }

    @Override public void onEnable(ModuleContext context) {
        if (!resumeHistory || world != context.client().world) reset();
        resumeHistory = false; enabled = true; active = this;
    }
    @Override public void onDisable(ModuleContext context) {
        enabled = false; if (active == this) active = null;
        if (context.module().enabled() && world != null && world == context.client().world) {
            suspend(); resumeHistory = true;
        } else reset();
    }
    @Override public void onWorldChanged(ModuleContext context) {
        if (world != context.client().world) reset();
    }
    private void suspend() {
        generation++;
        if (future != null) future.cancel(true);
        if (activityFuture != null) activityFuture.cancel(true);
        if (worker != null) worker.shutdownNow();
        future = null; activityFuture = null; worker = null; capture = null;
        cache.clearLive(); revisions.clear(); dirty.clear(); changedChunks.clear(); pending.clear(); results.clear();
        displayed = List.of(); displayedPaths = List.of(); discovery = List.of(); nextDiscovery = 0; failure = "";
        activityResult = null; activityScores = List.of(); activityFindings = Set.of();
        displayedActivity = List.of(); activityDirty = !activityEvidence.isEmpty();
        estimateSurfaces.clear(); nextSurface = 0; surfaceDirty = false; preferDirty = true;
        publicationDirty = false; activityMarkersDirty = false;
    }
    private void reset() {
        suspend(); world = null; resumeHistory = false; cache.clear(); classified.clear();
        activityEvidence.clear(); growthKinds.clear(); activityVersion++;
    }

    @Override public void onStartTick(ModuleContext context) {
        var client = context.client();
        if (client.world == null || client.player == null) { reset(); return; }
        if (!client.player.isAlive()) { suspend(); return; }
        int requestedFloor = (int) finite(context.slider("Scan floor Y", 0), 0, -64, 320);
        int actualFloor = Math.max(client.world.getBottomY(), requestedFloor);
        if (world != client.world || floor != actualFloor) {
            reset(); world = client.world; floor = actualFloor; top = world.getTopYInclusive() + 1;
            String server = client.getCurrentServerEntry() == null ? "local" : client.getCurrentServerEntry().address;
            session = server + "/" + world.getRegistryKey().getValue();
        }
        enabled = true; active = this; tick++;
        threshold = (int) finite(context.slider("Flag score", 35), 35, 5, 130);
        showReasons = context.toggle("Show reasons", false);
        showPaths = context.toggle("Path blocks", true);
        boolean nextShowActivity = context.toggle("Activity estimate", true);
        int nextActivityRadius = (int) finite(context.slider("Tick radius", 7), 7, 2, 12);
        if (showActivity != nextShowActivity || activityRadius != nextActivityRadius) {
            showActivity = nextShowActivity; activityRadius = nextActivityRadius;
            pruneActivityHistory();
            invalidateActivity();
        }
        EnumSet<Check> selected = EnumSet.noneOf(Check.class);
        for (int i = 0; i < LABELS.length; i++) if (context.toggle(LABELS[i], true)) selected.add(Check.values()[i]);
        if (!selected.equals(checks)) {
            checks = selected; generation++;
            if (future != null) future.cancel(true);
            future = null; results.clear(); pending.addAll(cache.live().keySet()); publish();
            activityEvidence.clear(); growthKinds.clear(); invalidateActivity();
        }
        if (floor >= top || !failure.isEmpty() || checks.isEmpty()) return;
        for (long id : changedChunks) {
            if (cache.live(id) != null) dirty.add(id);
            invalidate(SuspiciousSnapshots.x(id), SuspiciousSnapshots.z(id));
        }
        if (!changedChunks.isEmpty()) { changedChunks.clear(); publish(); }
        collectResult();
        int cx = client.player.getBlockX() >> 4, cz = client.player.getBlockZ() >> 4;
        if (cx != activityX || cz != activityZ) {
            activityX = cx; activityZ = cz;
            pruneActivityHistory();
            invalidateActivity();
        }
        collectActivity();
        // Keep a complete moving neighborhood in memory instead of continuously evicting
        // and rescanning a stationary client's entire high-distance chunk view.
        long worstChunkBytes = 1200L + ((top - Math.floorDiv(floor, 16) * 16 + 15) / 16) * 8224L;
        int memoryRadius = Math.max(1, ((int) Math.sqrt(SuspiciousSnapshots.Cache.MAX_BYTES / (worstChunkBytes * 2)) - 1) / 2);
        int nextRadius = Math.min(Math.min(MAX_RADIUS, memoryRadius), Math.max(1, client.options.getViewDistance().getValue()));
        if (discovery.isEmpty() || cx != discoverX || cz != discoverZ || nextRadius != radius) {
            discoverX = cx; discoverZ = cz; radius = nextRadius;
            ArrayList<Long> queue = new ArrayList<>();
            for (int ring = 0; ring <= radius; ring++) for (int dz = -ring; dz <= ring; dz++)
                for (int dx = -ring; dx <= ring; dx++) if (Math.max(Math.abs(dx), Math.abs(dz)) == ring)
                    queue.add(key(cx + dx, cz + dz));
            discovery = List.copyOf(queue); nextDiscovery = 0;
        }
        if (tick % 20 == 0) {
            boolean pruned = false;
            for (long id : cache.live().keySet()) if (!world.isChunkLoaded(SuspiciousSnapshots.x(id), SuspiciousSnapshots.z(id))
                || Math.abs((long) SuspiciousSnapshots.x(id) - cx) > radius
                || Math.abs((long) SuspiciousSnapshots.z(id) - cz) > radius)
                pruned |= discardChunk(SuspiciousSnapshots.x(id), SuspiciousSnapshots.z(id));
            dirty.removeIf(id -> !captureArea(id));
            changedChunks.removeIf(id -> !captureArea(id));
            revisions.keySet().removeIf(id -> cache.live(id) == null && (capture == null || capture.id != id));
            if (pruned) publish();
        }
        if (capture != null && !captureArea(capture.id)) { dirty.remove(capture.id); capture = null; }
        long deadline = System.nanoTime() + CAPTURE_NANOS;
        if (capture == null) selectCapture();
        if (capture != null) {
            WorldChunk loaded = loaded(capture.x, capture.z);
            if (loaded != capture.chunk || capture.revision != revisions.getOrDefault(capture.id, 0L)) {
                // A busy farm/liquid chunk must not monopolize retries ahead of new terrain.
                dirty.remove(capture.id);
                if (loaded != null) dirty.add(capture.id);
                capture = null;
            } else if (capture.advance(deadline)) {
                var completed = capture.finish();
                cache.put(completed); dirty.remove(capture.id); capture = null;
                results.entrySet().removeIf(e -> !inputsCurrent(e.getValue()));
                invalidate(completed.x, completed.z);
                publish();
            }
        }
        // Packet bursts, unloads, completed scans and surfaces publish once per tick.
        if (publicationDirty) { publicationDirty = false; publishNow(); }
        refreshActivitySurfaces();
        if (activityMarkersDirty) { activityMarkersDirty = false; flushActivityMarkers(); }
        launchAnalysis();
    }

    private void selectCapture() {
        if (preferDirty && selectDirtyCapture()) { preferDirty = false; return; }
        // Discovery is bounded too: a large view distance never walks every loaded chunk in one tick.
        for (int attempt = 0; attempt < 32 && !discovery.isEmpty(); attempt++) {
            if (nextDiscovery >= discovery.size()) nextDiscovery = 0;
            long id = discovery.get(nextDiscovery++);
            if (cache.live(id) != null) continue;
            WorldChunk chunk = loaded(SuspiciousSnapshots.x(id), SuspiciousSnapshots.z(id));
            if (chunk != null) { capture = new Capture(chunk); preferDirty = true; return; }
        }
        if (selectDirtyCapture()) preferDirty = false;
    }
    private boolean captureArea(long id) {
        return Math.abs((long) SuspiciousSnapshots.x(id) - discoverX) <= radius
            && Math.abs((long) SuspiciousSnapshots.z(id) - discoverZ) <= radius;
    }
    private boolean selectDirtyCapture() {
        for (Iterator<Long> iterator = dirty.iterator(); iterator.hasNext();) {
            long id = iterator.next();
            WorldChunk chunk = captureArea(id) ? loaded(SuspiciousSnapshots.x(id), SuspiciousSnapshots.z(id)) : null;
            if (chunk == null) { iterator.remove(); continue; }
            capture = new Capture(chunk); return true;
        }
        return false;
    }
    private WorldChunk loaded(int x, int z) {
        return world != null && world.isChunkLoaded(x, z)
            ? world.getChunkManager().getChunk(x, z, ChunkStatus.FULL, false) : null;
    }

    private void launchAnalysis() {
        if (future != null || activityFuture != null) return;
        if (showActivity && activityDirty && tick >= nextEstimate) {
            launchActivity();
            return;
        }
        if (pending.isEmpty()) return;
        long id = pending.iterator().next(); pending.remove(id);
        int x = SuspiciousSnapshots.x(id), z = SuspiciousSnapshots.z(id);
        if (cache.live(id) == null || dirty.contains(id) || loaded(x, z) == null) return;
        Map<Long, SuspiciousSnapshots.Chunk> now = new HashMap<>(), first = new HashMap<>();
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            long neighbor = key(x + dx, z + dz);
            var current = cache.live(neighbor);
            if (current == null || dirty.contains(neighbor) || loaded(x + dx, z + dz) == null) continue;
            now.put(neighbor, current);
            var original = cache.first(neighbor);
            if (original != null) first.put(neighbor, original);
        }
        if (now.isEmpty()) return;
        var currentTerrain = new SuspiciousSnapshots.View(now, floor, top);
        var firstTerrain = new SuspiciousSnapshots.View(first, floor, top);
        long jobGeneration = generation;
        EnumSet<Check> jobChecks = checks.clone();
        ensureWorker();
        future = worker.submit(() -> analyze(currentTerrain, firstTerrain, x, z, jobChecks, jobGeneration));
    }
    private void ensureWorker() {
        if (worker == null) worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Celeste suspicious chunks"); thread.setDaemon(true); return thread;
        });
    }

    // Worker-owned cache: unchanged snapshots are summarized once, reused by all overlapping windows.
    private final Map<SuspiciousSnapshots.Chunk, ChunkSummary> summaries = Collections.synchronizedMap(new WeakHashMap<>());
    private ChunkSummary summary(SuspiciousSnapshots.View terrain, SuspiciousSnapshots.Chunk chunk) {
        synchronized (summaries) {
            return summaries.computeIfAbsent(chunk, unused -> summarize(terrain, chunk.x, chunk.z));
        }
    }
    private Result analyze(SuspiciousSnapshots.View now, SuspiciousSnapshots.View first, int x, int z,
                           EnumSet<Check> jobChecks, long jobGeneration) throws InterruptedException {
        List<ChunkSummary> currentSummaries = new ArrayList<>(), firstSummaries = new ArrayList<>();
        for (var chunk : now.chunks().values()) {
            if (Thread.interrupted()) throw new InterruptedException();
            currentSummaries.add(summary(now, chunk));
        }
        for (var chunk : first.chunks().values()) firstSummaries.add(summary(first, chunk));
        Collection<Finding> geometry = List.of();
        List<PathBlock> pathBlocks = List.of();
        if (jobChecks.contains(Check.BURIED_ACCESS_PATH) || jobChecks.contains(Check.OPEN_SHAFTS)) {
            var job = new SuspiciousGeometry.Job(now, x, z, currentSummaries, jobChecks);
            while (!job.isDone()) {
                if (Thread.interrupted()) throw new InterruptedException();
                job.advance(512, System.nanoTime() + 500_000L);
                if (!job.isDone()) Thread.sleep(1);
            }
            geometry = job.findings();
            if (jobChecks.contains(Check.BURIED_ACCESS_PATH)) {
                pathBlocks = job.buriedBlocks().stream()
                    .map(pos -> new PathBlock(pos, now.cell(pos.x(), pos.y(), pos.z())))
                    .toList();
            }
        }
        var evaluation = evaluate(now, first, x, z, currentSummaries, firstSummaries, jobChecks, geometry);
        Map<Long, Long> versions = new HashMap<>();
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            long neighbor = key(x + dx, z + dz);
            var input = now.chunks().get(neighbor);
            versions.put(neighbor, input == null ? -1L : input.revision);
        }
        return new Result(key(x, z), jobGeneration, Map.copyOf(versions), evaluation, pathBlocks);
    }
    private void collectResult() {
        if (future == null || !future.isDone()) return;
        try {
            Result result = future.get();
            boolean fresh = result.generation == generation;
            for (var e : result.inputs.entrySet()) {
                var current = cache.live(e.getKey());
                fresh &= (current == null ? -1L : current.revision) == e.getValue() && !dirty.contains(e.getKey());
            }
            if (fresh) {
                results.remove(result.id); results.put(result.id, result);
                while (results.size() > MAX_RESULTS) results.remove(results.keySet().iterator().next());
                rememberGrowth(result.evaluation);
                publish();
            } else if (cache.live(result.id) != null) pending.add(result.id);
        } catch (CancellationException ignored) {
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failed) {
            failure = "Scan paused; toggle the module to retry";
            dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/SuspiciousChunks")
                .error("Suspicious Chunk Finder analysis failed", failed.getCause());
            results.clear(); publish();
        } finally { future = null; }
    }
    private boolean inputsCurrent(Result result) {
        for (var e : result.inputs.entrySet()) {
            var current = cache.live(e.getKey());
            if ((current == null ? -1L : current.revision) != e.getValue() || dirty.contains(e.getKey())) return false;
        }
        return true;
    }
    private void publish() {
        publicationDirty = true;
    }
    private void publishNow() {
        displayed = List.copyOf(results.values());
        LinkedHashMap<Pos, PathBlock> blocks = new LinkedHashMap<>();
        int visited = 0;
        outer: for (Result result : displayed) for (PathBlock block : result.pathBlocks) {
            if (++visited > PATH_PUBLICATION_VISITS || blocks.size() >= MAX_PATH_BLOCKS) break outer;
            blocks.putIfAbsent(block.pos, block);
        }
        displayedPaths = List.copyOf(blocks.values());
        Set<Finding> findings = activityFindings();
        if (!findings.equals(activityFindings)) {
            // Added positive evidence cannot lower a score. Keep the previous
            // result visible while refreshing; removals and new shafts clear it.
            boolean additive = findings.containsAll(activityFindings)
                && findings.stream().noneMatch(f -> f.check() == Check.OPEN_SHAFTS && !activityFindings.contains(f));
            activityFindings = findings;
            invalidateActivity(!additive);
        }
        publishActivity();
    }

    private void invalidateActivity() {
        invalidateActivity(true);
    }
    private void invalidateActivity(boolean clearCompleted) {
        activityVersion++;
        if (activityFuture != null) activityFuture.cancel(true);
        activityFuture = null; activityDirty = true;
        if (clearCompleted) {
            activityResult = null; activityScores = List.of(); displayedActivity = List.of();
        }
    }
    private void rememberGrowth(Evaluation evaluation) {
        boolean changed = false;
        for (GrowthObservation sample : evaluation.growthObservations()) {
            long id = key(sample.blockX() >> 4, sample.blockZ() >> 4);
            if (!retainActivityHistory(id)) continue;
            GrowthObservation previous = activityEvidence.get(id);
            if (previous == null) {
                activityEvidence.put(id, sample); changed = true;
            } else if (SuspiciousActivity.representative(sample, previous) == sample && !sample.equals(previous)) {
                activityEvidence.put(id, sample); changed = true;
            }
        }
        // One physical kelp tip can qualify both growth checks while the fitter
        // intentionally keeps one positional observation. Preserve both score reasons.
        for (var entry : evaluation.growthChecks().entrySet())
            for (Check check : entry.getValue()) rememberGrowthKind(entry.getKey(), check);
        if (changed) invalidateActivity();
    }
    private void rememberGrowthKind(long id, Check check) {
        if (!retainActivityHistory(id)) return;
        growthKinds.computeIfAbsent(id, unused -> EnumSet.noneOf(Check.class)).add(check);
    }
    private boolean retainActivityHistory(long id) {
        // Keep every fitter input plus a small movement margin. This bounds storage
        // geographically without permanently disabling long flyovers after 512 chunks.
        long range = activityRadius * 2L + 2;
        return Math.abs((long) SuspiciousSnapshots.x(id) - activityX) <= range
            && Math.abs((long) SuspiciousSnapshots.z(id) - activityZ) <= range;
    }
    private void pruneActivityHistory() {
        activityEvidence.keySet().removeIf(id -> !retainActivityHistory(id));
        growthKinds.keySet().removeIf(id -> !retainActivityHistory(id));
        activityFindings = activityFindings();
    }
    private void launchActivity() {
        activityDirty = false; nextEstimate = tick + 20;
        // Independent distant areas must not be fitted as one player's ticking footprint.
        List<GrowthObservation> local = activityEvidence.values().stream().filter(sample ->
            Math.abs((long) (sample.blockX() >> 4) - activityX) <= activityRadius * 2L
                && Math.abs((long) (sample.blockZ() >> 4) - activityZ) <= activityRadius * 2L).toList();
        long version = activityVersion, sessionGeneration = generation;
        int radius = activityRadius;
        ensureWorker();
        Set<Finding> findings = activityFindings;
        EnumSet<Check> jobChecks = checks.clone();
        activityFuture = worker.submit(() -> {
            var estimate = SuspiciousActivity.estimate(local, radius);
            return new ActivityResult(sessionGeneration, version, estimate,
                SuspiciousActivity.score(estimate, findings, jobChecks));
        });
    }
    private void collectActivity() {
        if (activityFuture == null || !activityFuture.isDone()) return;
        try {
            ActivityResult result = activityFuture.get();
            if (result.generation == generation && result.version == activityVersion) {
                HashSet<Long> candidates = new HashSet<>();
                for (var scored : result.scores) candidates.add(key(scored.candidate().chunkX(), scored.candidate().chunkZ()));
                estimateSurfaces.keySet().retainAll(candidates); nextSurface = 0;
                activityResult = result.estimate; activityScores = result.scores; publishActivity();
            }
        } catch (CancellationException ignored) {
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failed) {
            activityResult = null; activityScores = List.of(); displayedActivity = List.of();
            dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/SuspiciousChunks")
                .error("Suspicious activity estimate failed", failed.getCause());
        } finally { activityFuture = null; }
    }
    private Set<Finding> activityFindings() {
        // Findings retain their actual feature locations. Old scoring-window centers
        // are never locations to display or reference for the shaft deduction.
        LinkedHashSet<Finding> evidence = new LinkedHashSet<>();
        for (Result result : displayed) for (Finding finding : result.evaluation.findings()) {
            // Growth reasons below come from actual qualifying sample chunks,
            // including every bed; population seed positions can be less precise.
            if (finding.check() != Check.GROUNDED_VINES && finding.check() != Check.MATURE_GEODE
                && finding.check() != Check.SURFACE_KELP && finding.check() != Check.MATURE_KELP_AGE)
                evidence.add(finding);
        }
        for (var entry : growthKinds.entrySet()) {
            int x = SuspiciousSnapshots.x(entry.getKey()), z = SuspiciousSnapshots.z(entry.getKey());
            for (Check check : entry.getValue()) evidence.add(new Finding(check, entry.getKey(), x, z));
        }
        return Set.copyOf(evidence);
    }
    private void publishActivity() {
        activityMarkersDirty = true;
    }
    private void flushActivityMarkers() {
        if (!showActivity || activityResult == null) { displayedActivity = List.of(); return; }
        // Scoring runs on the immutable worker. The game thread only binds
        // completed candidate scores to currently known surface heights.
        ArrayList<ActivityMarker> markers = new ArrayList<>();
        for (var scored : activityScores) {
            var candidate = scored.candidate();
            long id = key(candidate.chunkX(), candidate.chunkZ());
            var terrain = cache.live(id);
            int surface = terrain == null || dirty.contains(key(candidate.chunkX(), candidate.chunkZ()))
                ? Integer.MIN_VALUE : terrain.representativeSurface();
            if (terrain == null) {
                var saved = estimateSurfaces.get(id);
                if (saved != null) surface = saved.height;
            }
            markers.add(new ActivityMarker(candidate.chunkX(), candidate.chunkZ(),
                surface >= floor ? surface + 1.025 : Double.NaN, scored.evaluation()));
        }
        displayedActivity = List.copyOf(markers);
    }

    private void refreshActivitySurfaces() {
        if (!showActivity || activityScores.isEmpty()) return;
        long deadline = System.nanoTime() + 250_000L;
        int acquired = 0;
        for (int visited = 0; visited < Math.min(64, activityScores.size()) && acquired < 4; visited++) {
            if (System.nanoTime() >= deadline) break;
            if (nextSurface >= activityScores.size()) nextSurface = 0;
            var candidate = activityScores.get(nextSurface++).candidate();
            long id = key(candidate.chunkX(), candidate.chunkZ());
            if (cache.live(id) != null) continue;
            WorldChunk chunk = loaded(candidate.chunkX(), candidate.chunkZ());
            SurfaceSample saved = estimateSurfaces.get(id);
            if (chunk == null) {
                if (saved != null) { estimateSurfaces.remove(id); surfaceDirty = true; }
                continue;
            }
            if (saved != null && saved.chunk == chunk) continue;
            int height = existingSurface(chunk); acquired++;
            if (height != Integer.MIN_VALUE) {
                estimateSurfaces.put(id, new SurfaceSample(chunk, height)); surfaceDirty = true;
            } else if (saved != null) { estimateSurfaces.remove(id); surfaceDirty = true; }
        }
        if (surfaceDirty) { surfaceDirty = false; publishActivity(); }
    }
    private int existingSurface(WorldChunk chunk) {
        // Read only heightmaps Minecraft already has. Never populate a missing
        // heightmap (which could scan hidden terrain below the configured floor).
        Heightmap selected = null;
        int rank = 0;
        for (var entry : chunk.getHeightmaps()) {
            int next = entry.getKey() == Heightmap.Type.MOTION_BLOCKING_NO_LEAVES ? 3
                : entry.getKey() == Heightmap.Type.MOTION_BLOCKING ? 2
                : entry.getKey() == Heightmap.Type.WORLD_SURFACE ? 1 : 0;
            if (next > rank) { selected = entry.getValue(); rank = next; }
        }
        if (selected == null) return Integer.MIN_VALUE;
        int[] heights = new int[256];
        for (int i = 0; i < heights.length; i++) {
            int height = selected.getOneLower(i & 15, i >> 4);
            heights[i] = height < floor ? floor - 1 : height >= top ? Integer.MIN_VALUE : height;
        }
        Arrays.sort(heights);
        return heights[heights.length * 3 / 4];
    }

    public void extract(WorldExtractionContext context, List<VisualWorldRenderer.WorldBox> boxes) {
        if (!enabled || world == null || context.world() != world) return;
        if (showPaths && checks.contains(Check.BURIED_ACCESS_PATH)) {
            var client = MinecraftClient.getInstance();
            if (client.player == null || client.world != world) return;
            int examined = 0, drawn = 0;
            for (PathBlock block : displayedPaths) {
                if (examined++ >= 1024 || drawn >= 512) break;
                Pos point = block.pos;
                BlockPos pos = new BlockPos(point.x(), point.y(), point.z());
                if (!world.isChunkLoaded(pos)) continue;
                BlockState state = world.getBlockState(pos);
                // A removed or replaced filler block must disappear before analysis catches up.
                if (classifyState(state) != block.cell || state.isAir()) continue;
                var shape = state.getOutlineShape(world, pos, ShapeContext.of(client.player));
                if (shape.isEmpty()) continue;
                Box box = shape.getBoundingBox().offset(pos).expand(.002);
                if (context.frustum() != null && !context.frustum().isVisible(box)) continue;
                boxes.add(new VisualWorldRenderer.WorldBox(box, 0x18FFB85C, 0xD9FFB85C, 1.2f));
                drawn++;
            }
        }
        if (showActivity) for (ActivityMarker marker : displayedActivity) {
            if (!marker.evaluation.qualifies(threshold) || !Double.isFinite(marker.y)
                || !world.isChunkLoaded(marker.x, marker.z)) continue;
            Box footprint = new Box(marker.x * 16.0, marker.y, marker.z * 16.0,
                marker.x * 16.0 + 16, marker.y, marker.z * 16.0 + 16);
            if (context.frustum() != null && !context.frustum().isVisible(footprint.expand(0, .03, 0))) continue;
            boxes.add(new VisualWorldRenderer.WorldBox(footprint, 0x35FF3838, 0, 0, 1,
                VisualWorldRenderer.Shape.FOOTPRINT, false));
        }
    }
    private void reasons(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || !showReasons || !AuthBootstrap.authorized() || world != client.world || client.player == null
            || client.options.hudHidden || client.currentScreen != null) return;
        ActivityMarker nearest = null; double best = Double.MAX_VALUE;
        if (showActivity) for (ActivityMarker marker : displayedActivity) {
            double dx = marker.x * 16.0 + 8 - client.player.getX();
            double dz = marker.z * 16.0 + 8 - client.player.getZ();
            if (dx * dx + dz * dz < best) { nearest = marker; best = dx * dx + dz * dz; }
        }
        ArrayList<String> lines = new ArrayList<>();
        if (!failure.isEmpty()) lines.add(failure);
        else if (floor >= top) lines.add("Scan floor is above this world's terrain");
        else if (nearest != null) {
            lines.add("Possible activity: " + nearest.x + ", " + nearest.z
                + "  |  " + nearest.evaluation.score());
            if (!nearest.evaluation.qualifies(threshold)) lines.add("Below flag score " + threshold);
            for (Check check : Check.values()) {
                int amount = nearest.evaluation.contributions().getOrDefault(check, 0);
                if (amount != 0) lines.add((check == Check.OPEN_SHAFTS ? "Open shaft" : LABELS[check.ordinal()])
                    + ": " + (amount > 0 ? "+" : "") + amount);
            }
        }
        if (showActivity) {
            if (activityResult == null) lines.add("Activity estimate: gathering growth evidence");
            else if (activityResult.candidates().isEmpty()) lines.add(switch (activityResult.status()) {
                case INSUFFICIENT -> "Activity estimate: gathering growth evidence";
                case BROAD -> "Activity estimate: gathering growth evidence";
                case INCONSISTENT -> "Activity estimate: no shared area at this radius";
                case BUDGET_LIMIT -> "Activity estimate: too much evidence for one area";
                default -> "Activity estimate: gathering growth evidence";
            });
        }
        if (cache.historyFull()) lines.add("History full; toggle to start a new scan");
        int y = Math.max(12, context.getScaledWindowHeight() / 2 - lines.size() * 5);
        for (String line : lines) {
            context.drawTextWithShadow(client.textRenderer, line, 8, y, 0xFFF0D7D7); y += 11;
        }
    }

    private final class Capture {
        final WorldChunk chunk;
        final int x, z, base;
        final long id, revision, time = System.currentTimeMillis();
        final short[][] sections;
        final int[] surfaces = new int[256];
        int section, index;
        Capture(WorldChunk chunk) {
            this.chunk = chunk; x = chunk.getPos().x; z = chunk.getPos().z; id = key(x, z);
            revision = revisions.getOrDefault(id, 0L); base = Math.floorDiv(floor, 16) * 16;
            sections = new short[(top - base + 15) / 16][];
            Arrays.fill(surfaces, floor - 1);
        }
        boolean advance(long deadline) {
            int visited = 0;
            while (section < sections.length && visited < CAPTURE_BLOCKS) {
                if ((visited & 63) == 0 && System.nanoTime() >= deadline) return false;
                int sectionY = (base >> 4) + section;
                int actual = world.sectionCoordToIndex(sectionY);
                ChunkSection states = chunk.getSectionArray()[actual];
                if (states == null) { section++; index = 0; visited++; continue; }
                if (states.isEmpty()) { sections[section++] = new short[]{(short) AIR}; index = 0; visited++; continue; }
                if (sections[section] == null) sections[section] = new short[4096];
                int y = base + section * 16 + (index >> 8);
                if (y < floor || y >= top) sections[section][index] = (short) UNKNOWN;
                else {
                    Classified type = classify(states.getBlockState(index & 15, index >> 8, index >> 4 & 15));
                    sections[section][index] = type.code;
                    if (type.surface) surfaces[index & 255] = y;
                }
                visited++;
                if (++index == 4096) {
                    short[] values = sections[section]; boolean uniform = true;
                    for (int i = 1; i < values.length; i++) if (values[i] != values[0]) { uniform = false; break; }
                    if (uniform) sections[section] = new short[]{values[0]};
                    section++; index = 0;
                }
            }
            if (section < sections.length) return false;
            // Use the heightmap's predicate during the bounded scan. Asking Minecraft to
            // populate an absent heightmap here could scan a whole column below the cutoff.
            for (int s = 0; s < sections.length; s++) if (sections[s] == null)
                for (int column = 0; column < 256; column++)
                    if (surfaces[column] < base + (s + 1) * 16) surfaces[column] = Integer.MIN_VALUE;
            return true;
        }
        SuspiciousSnapshots.Chunk finish() {
            return new SuspiciousSnapshots.Chunk(x, z, floor, top, sections, surfaces, time, ++snapshotSerial);
        }
    }

    private Classified classify(BlockState state) {
        Classified known = classified.get(state); if (known != null) return known;
        Classified value = new Classified((short) classifyState(state),
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES.getBlockPredicate().test(state));
        classified.put(state, value); return value;
    }
    private static int classifyState(BlockState state) {
        if (state.isAir()) return AIR;
        Block b = state.getBlock();
        if (b == Blocks.WATER) return state.getFluidState().getLevel() == 8 ? WATER : FLOWING_WATER;
        if (b == Blocks.LAVA) return LAVA;
        if (b == Blocks.DEEPSLATE) return state.get(Properties.AXIS) == Direction.Axis.Y ? DEEPSLATE : HORIZONTAL_DEEPSLATE;
        if (b == Blocks.STONE || b == Blocks.GRANITE || b == Blocks.ANDESITE || b == Blocks.DIORITE || b == Blocks.TUFF) return STONE;
        if (b == Blocks.DIRT) return DIRT;
        if (b == Blocks.COARSE_DIRT) return COARSE_DIRT;
        if (b == Blocks.ROOTED_DIRT) return ROOTED_DIRT;
        if (b == Blocks.PODZOL) return PODZOL;
        if (b == Blocks.MYCELIUM) return MYCELIUM;
        if (b == Blocks.VINE) return VINE;
        if (b == Blocks.KELP) return cell(KELP, state.get(Properties.AGE_25), 0);
        if (b == Blocks.KELP_PLANT) return KELP_PLANT;
        if (b == Blocks.BUDDING_AMETHYST) return BUDDING;
        if (b == Blocks.AMETHYST_BLOCK) return AMETHYST;
        if (b == Blocks.CALCITE) return CALCITE;
        if (b == Blocks.SMOOTH_BASALT) return SMOOTH_BASALT;
        if (b == Blocks.BASALT) return BASALT;
        if (b instanceof AmethystClusterBlock) {
            int type = b == Blocks.AMETHYST_CLUSTER ? CLUSTER : b == Blocks.LARGE_AMETHYST_BUD ? LARGE_BUD
                : b == Blocks.MEDIUM_AMETHYST_BUD ? MEDIUM_BUD : SMALL_BUD;
            return cell(type, 0, state.get(Properties.FACING).getIndex());
        }
        if (b == Blocks.COBBLESTONE) return COBBLE;
        if (b == Blocks.COBBLED_DEEPSLATE) return COBBLED_DEEPSLATE;
        if (!state.getFluidState().isEmpty()) return WATERLOGGED_OTHER;
        String id = Registries.BLOCK.getId(b).getPath();
        if (id.contains("bricks") || id.contains("planks") || id.contains("spawner") || id.contains("rail")
            || id.contains("chest") || id.contains("polished") || id.equals("mossy_cobblestone")
            || id.startsWith("chiseled_") || id.contains("copper") || id.equals("bookshelf")) return STRUCTURE;
        return OTHER;
    }
    private static double finite(double value, double fallback, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }
    private record Result(long id, long generation, Map<Long, Long> inputs,
                          Evaluation evaluation, List<PathBlock> pathBlocks) {
        Result { pathBlocks = List.copyOf(pathBlocks); }
    }
    private record PathBlock(Pos pos, int cell) { }
    private record SurfaceSample(WorldChunk chunk, int height) { }
    private record ActivityMarker(int x, int z, double y, Evaluation evaluation) { }
    private record ActivityResult(long generation, long version, SuspiciousActivity.Result estimate,
                                  List<SuspiciousActivity.ScoredCandidate> scores) { }
    private record Classified(short code, boolean surface) { }
}
