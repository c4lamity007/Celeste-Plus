# Suspicious Chunk Finder

This Celeste+ visual module analyzes local 3×3 windows for evidence, then estimates a possible activity location from actual growth positions. Only estimated chunks meeting `Flag score` receive flat, translucent red 16×16 surface markers. The old red evidence-window markers are removed. Suspected buried-path blocks retain amber Block ESP outlines. `Show reasons` displays the nearest estimate's score, contributing evidence and estimation status.

## Server setup

The user clarified that there is no plugin changing the ticking footprint. The **3×3 analysis window finds local features**, not an assertion that vanilla ticks exactly nine chunks. Its center is never used as an estimated activity position. The user selected **Y=0 provisionally**; `Advanced → Scan floor Y` can change it. The scanner reads every available block height from that floor through the dimension's top. Below-floor and unloaded cells are unknown. If the server substitutes believable blocks above the chosen floor, a client cannot reliably distinguish those substitutions from real terrain; the cutoff must match that server.

Vanilla random ticking is not a guaranteed circle around a player. Mojang changed it to operate inside simulation distance or in chunks loaded by other chunk-loading sources, such as ender pearls, in [25w06a](https://www.minecraft.net/en-us/article/minecraft-snapshot-25w06a). The configurable radius below is therefore an approximate activity model, not an inferred server setting or proof of a base.

The active capture area is the nearest **11×11 loaded chunks** at most, limited by view distance and memory. Local feature analysis uses 3×3 windows; at the active boundary, missing neighbors stay unknown. Compact first-observation growth evidence can remain across flyovers. No chunks are requested or force-loaded.

## Settings and scores

The module starts disabled. `Flag score` defaults to **35**, ranges from **5 to 130**, and changes in steps of five. All nine check toggles start enabled. `Path blocks` and `Activity estimate` start enabled. `Evidence chunks` is removed; its old saved value has no effect. `Tick radius` defaults to **7 chunks**, ranges from 2–12, and is shown with activity estimation enabled. `Show reasons` and `Advanced` start off. Weights and sampling rules are internal.

| Check | Weight | Initial qualifying criteria |
| --- | ---: | --- |
| Rotated deepslate | +30 | Horizontal actual axis; 26 known immediate neighbors, at least 20 natural-rock neighbors including 12 vertical deepslate; no structural marker within two blocks. |
| Mismatched soil | +10 | Connected 2–12 dirt blocks, at most 4×3×4, compact fill of at least 75%; near surface; at least eight surrounding soil samples with 80% suitable and 80% consistent soil. |
| Grounded vines | +10 | At least eight independent bottommost endings; at least 75% finish immediately above accepted natural stone. |
| Mature geode | +15 | Recognized, fully observed geode and at least 12 correctly attached crystals; at least 80% mature clusters. |
| Missing budding | +25 | Recognized geode with at least 64 inner amethyst/budding blocks; budding fraction at most 2%. |
| Surface kelp | +15 | At least 12 independent tips in a local bed, at least 80% with air above; sufficient submerged stalk and wet surroundings. |
| Mature kelp | +15 | Same population requirements; at least 75% tips age 24–25. |
| Buried paths | +35 | At least six connected anomalous filler blocks spanning five blocks, narrow shaft/tunnel/stair geometry and natural-rock context; fluid/structure exclusions. |
| Open shafts | −20 | Qualifying deep, open route within **three chunks of the estimated location**. Surface-open narrow, face-connected route; at least 24 blocks deep, within eight blocks of the floor, at least 80% of surface-to-floor depth and 80% narrow levels. Sideways bends and short landings allowed. |

For each estimated candidate, positive checks use their actual feature chunks within the configured `Tick radius`. Each check contributes once. The two geode checks share a **25-point cap**; the two kelp checks share a **20-point cap**. Positive maximum is **130**. Open shafts use a fixed, inclusive three-chunk Euclidean distance from that candidate: `dx² + dz² <= 9`. For example, offsets (3,0) and (2,2) qualify, while (3,1) does not. This distance never references the old evidence-window center. Apply at most one 20-point deduction, then clamp the result to zero. A score **equal to the threshold qualifies** for a red estimate marker. Without an established estimate, no shaft is flagged or deducted in the displayed score. These are uncalibrated suspicion scores, not percentage probabilities. Sparse crystals is removed from both detection and settings; old saved values have no effect.

## Buried-path outlines

`Path blocks` highlights only actual anomalous filler cells belonging to a completed, qualifying buried route. Seeds, failed routes and exhausted traversals do not receive boxes. The same route found by overlapping windows is deduplicated. Outlines use the shared Block ESP `WorldBox` pipeline and current block outline shapes, and do not require Block ESP itself to be enabled. They remain available even when the overall chunk score is below the flag threshold.

Results retain all nine source revisions. Updates discard affected geometry, and rendering checks the current block classification so removed/replaced filler does not leave stale boxes. Publication retains at most 2,048 unique blocks, inspects at most 8,192 source entries, and rendering examines at most 1,024 candidates and emits 512 boxes per frame. The detector retains its first qualifying route per analysis window.

## Possible activity area

The estimator uses actual positive growth sample positions from qualified first-observation populations: grounded vine endings, surface/aged kelp tips and fully mature attached geode crystals. It never treats the center of a flagged 3×3 window as the evidence location. Population minimums and ratios still apply. Missing budding and repaired blocks are not growth-position constraints.

Overlapping analyses deduplicate the same physical samples. A deterministic actual sample represents each evidence chunk, so a dense kelp forest does not receive a separate vote per plant. Compact evidence summaries are retained within twice the configured radius plus a two-chunk movement margin, including across terrain unloads. This is at most 2,809 chunks at radius 12. Summaries outside that region cannot affect the current local fit and are discarded; a long flight no longer permanently disables estimates after 512 observations. Every chunk inside the actual fitting region remains eligible (at most 2,401 at radius 12). Changing checks or beginning a new session clears this estimator history. Returning to an evicted first-observation snapshot still does not establish a newer growth baseline.

For the local area within twice the configured radius of the player, each evidence chunk votes for candidate chunk centers within the configured radius of its actual growth sample. Every candidate with the highest support is retained. This includes broad areas and equally supported separate areas; there is no four-marker limit or adjacency requirement. A qualified population in one evidence chunk can contribute locations, without needing a wide spread on both axes. Unknown terrain and chunks without suitable plants are never negative evidence. Dense populations still receive just one vote per evidence chunk.

When all evidence has a common fit only between chunk centers, an exact shared point preserves the chunks touching that point, including edge/corner cases. Otherwise, conflicting footprints use the strongest available support instead of suppressing every result or choosing a midpoint in empty terrain. This is a best-fit heuristic: positions are evaluated at chunk centers with that boundary fallback, not an exhaustive search of every fractional block location.

Each candidate still needs `Flag score`, a known surface height and a currently loaded chunk to receive a red marker. Candidates outside the full terrain-capture area can use an existing Minecraft heightmap: at most four new heightmaps and 64 candidates are examined per tick under a 0.25 ms loop deadline. No missing heightmap is populated, no below-cutoff block is scanned, and no chunk is requested. The preferred heightmap excludes leaves; an already available motion-blocking/world-surface map is the fallback. Unknown heights remain hidden until available. No uncertainty labels are added. The optional `Show reasons` retains score contributions and below-threshold information. Scoring runs with fitting on the immutable worker; the game thread only binds completed scores to current surface heights. Invalidated findings cancel stale work. Empty evidence or exhausted work budgets yield no markers rather than a partially evaluated ranking.

Candidate voting visits only the small radius grid around each observation; it never walks the empty distance between widely separated coordinates. A 5,625-cell work map bounds the current local scan area at the maximum radius. This is a memory/work guard, not a four-location display limit. The shared two-million-operation budget and cancellation checks remain in place. Exact common-intersection checks use only convex-hull constraints, while every evidence chunk still contributes its vote. Scoring deduplicates enabled findings and groups them by chunk before candidate distance checks.

This is a possible earlier activity area, not triangulation from known distance measurements. Multiple players, chunk loaders, player movement, biome distribution, natural maturity and previous visits can all distort the footprint. The server simulation distance and random-tick settings are not discovered automatically.

Plant population windows overlap to avoid arbitrary chunk-edge splits. Each covers up to 24×48×24 blocks within the evaluated neighborhood. Kelp tips need two plant segments below and at least two wet horizontal neighbors; short shallow stalks are excluded. Accepted natural-rock context includes stone, granite, andesite, diorite, tuff and deepslate, with basalt/smooth basalt distinguished for geodes.

Geode recognition requires at least 32 inner blocks, 12 calcite blocks, eight smooth-basalt blocks, eight cavity cells and extents of 4–24 blocks on each axis. The complete bounding volume plus a two-block margin must be known; clipped geodes are excluded. Bud attachment direction is checked against its supporting budding block. Ordinary stone/dirt texture orientation is not a stored rotation and is never used.

## History and lifecycle

The in-memory scan session is scoped to the current server and dimension. Chunk coordinates and first observation timestamps accompany immutable classified terrain. Growth evidence uses the first received valid cells. Later observations fill only previously unknown cells; they cannot replace old ages or crystal stages. Replacement/route evidence uses current observations.

Respawning preserves first observations in the same world. Disconnect, dimension change, an explicit module disable, or a scan-floor change starts a fresh session. Unloading discards current terrain but retains first observations while within the history budget. If an old baseline was evicted, revisiting that chunk does **not** silently establish an older-growth baseline: growth evidence stays unknown for that chunk until an explicit new session.

Block-update packets are coalesced by chunk and invalidate affected centers. Dirty captures alternate with new terrain discovery, and interrupted captures move to the back of the dirty queue. A continuously changing chunk can remain inconclusive, but cannot monopolize scan retries. Captures outside the current scan area are abandoned. Result/path publication and marker rebuilding are batched once per tick. In-flight results bind all nine input versions, including absent neighbors. Stale results are discarded after updates, unloads and memory eviction.

## Work and memory limits

- World/block access occurs only on the client thread. The worker receives immutable primitive terrain and no Minecraft world/entity/chunk objects.
- Capture is incremental: at most 8,192 block visits with a one-millisecond loop deadline per tick (snapshot finalization and result publication add bounded work outside that loop); empty sections are skipped. Surface heights are derived during that same above-floor scan with Minecraft's no-leaves heightmap predicate.
- Immutable surface quartiles are computed once per snapshot; per-neighborhood history lookup does not copy the entire history map. Uniform air, ordinary rock and other noncandidate snapshot sections skip per-block summary lookups.
- Disabled geometry checks skip their entire phase; disabling every check stops capture/analysis work.
- One cancellable worker and one outstanding result; no accumulating executor queue. Cached chunk summaries are reused for overlapping windows.
- Geometry advances in at most 512 bounded work units or 0.5 ms per slice, then yields. A unit includes a bounded number of neighbor probes; it is not one raw block lookup.
- Open shafts stop at 4,096 visited nodes per candidate; filled routes at 1,024. Exhausted candidates are inconclusive, never qualifying evidence.
- Geodes: at most 16 connected components per window and 4,096 visited cells per component. Candidate overflow makes the affected summary incomplete.
- At most 8,192 packed candidate positions per chunk: four bytes each, rather than one retained Java object per block. Mostly uninteresting dirt interiors are omitted as seeds; connected-feature traversal still examines their geometry.
- Classified snapshot storage is capped at 64 MiB and 512 live/first entries. First-seen identities are capped at 16,384; new growth baselines stop when that limit is reached. A maximum of 512 results is retained.

## Verification and limitations

The focused Java fixtures exercise the production analysis, geometry and snapshot cache against deterministic positive, negative, natural-generation and incomplete-data scenes. The full Minecraft runtime build is a separate gate. Neither establishes live-server detection accuracy or FPS; no actual in-game test has been performed for this release.

Continuous edits can keep a chunk's incremental capture inconclusive; it waits for a consistent capture instead of scoring mixed versions. First capture itself takes time, so some local ticking before observation is unavoidable. Oversized connected dirt and amethyst components are not retried as smaller qualifying tail fragments. Complete natural geodes can still have unusual crystal distributions. Natural vine/kelp growth and open caves do not identify who caused updates or prove that a base exists. Structure-material exclusions are conservative contextual heuristics, not authoritative server structure metadata. Perfectly restored block types/states leave no recoverable placement history.

The checked vanilla 1.21.11 assumptions and bytecode evidence are recorded in `suspicious-chunk-finder-vanilla-evidence.md`. Recheck them for a Minecraft version change.
