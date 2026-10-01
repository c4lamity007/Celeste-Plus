# Suspicious Chunk Finder

This Celeste+ visual module scores the center chunk and its eight neighbors. Neighbor-only evidence can flag the center. Each qualifying center gets one flat, translucent red 16×16 surface marker. `Show reasons` displays the nearest flagged chunk and its actual capped contributions.

## Server setup

The target server reportedly ticks a 3×3 column neighborhood above underground players. This is a server-specific assumption, not a vanilla rule. The user selected **Y=0 provisionally**; `Advanced → Scan floor Y` can change it. The scanner reads every available block height from that floor through the dimension's top. Below-floor and unloaded cells are unknown. If the server substitutes believable blocks above the chosen floor, a client cannot reliably distinguish those substitutions from real terrain; the cutoff must match that server.

The active capture area is the nearest **11×11 loaded chunks** at most, limited by view distance and memory. Every candidate still uses exactly its own 3×3 window; at the active boundary, missing neighbors stay unknown. The area follows the player. No chunks are requested or force-loaded.

## Settings and scores

The module starts disabled. `Flag score` defaults to **35**, ranges from **5 to 130**, and changes in steps of five. All ten check toggles start enabled. `Show reasons` and `Advanced` start off. Weights and sampling rules are internal.

| Check | Weight | Initial qualifying criteria |
| --- | ---: | --- |
| Rotated deepslate | +30 | Horizontal actual axis; 26 known immediate neighbors, at least 20 natural-rock neighbors including 12 vertical deepslate; no structural marker within two blocks. |
| Mismatched soil | +10 | Connected 2–12 dirt blocks, at most 4×3×4, compact fill of at least 75%; near surface; at least eight surrounding soil samples with 80% suitable and 80% consistent soil. |
| Grounded vines | +10 | At least eight independent bottommost endings; at least 75% finish immediately above accepted natural stone. |
| Mature geode | +15 | Recognized, fully observed geode and at least 12 correctly attached crystals; at least 80% mature clusters. |
| Sparse crystals | +15 | Recognized geode, at least 20 eligible budding faces, at most 15% occupied by correctly attached crystals. Air and full source-water cells qualify; flowing water does not. |
| Missing budding | +25 | Recognized geode with at least 64 inner amethyst/budding blocks; budding fraction at most 2%. |
| Surface kelp | +15 | At least 12 independent tips in a local bed, at least 80% with air above; sufficient submerged stalk and wet surroundings. |
| Mature kelp | +15 | Same population requirements; at least 75% tips age 24–25. |
| Buried paths | +35 | At least six connected anomalous filler blocks spanning five blocks, narrow shaft/tunnel/stair geometry and natural-rock context; fluid/structure exclusions. |
| Open shafts | −20 | Surface-open narrow, face-connected route; at least 24 blocks deep, within eight blocks of the floor, at least 80% of surface-to-floor depth and 80% narrow levels. Sideways bends and short landings allowed. |

Each check contributes once per 3×3 evaluation. The three geode checks share a **25-point cap**; the two kelp checks share a **20-point cap**. Positive maximum is **130**. Apply at most one shaft deduction, then clamp the result to zero. A score **equal to the threshold qualifies**. These are uncalibrated suspicion scores, not percentage probabilities.

Plant population windows overlap to avoid arbitrary chunk-edge splits. Each covers up to 24×48×24 blocks within the evaluated neighborhood. Kelp tips need two plant segments below and at least two wet horizontal neighbors; short shallow stalks are excluded. Accepted natural-rock context includes stone, granite, andesite, diorite, tuff and deepslate, with basalt/smooth basalt distinguished for geodes.

Geode recognition requires at least 32 inner blocks, 12 calcite blocks, eight smooth-basalt blocks, eight cavity cells and extents of 4–24 blocks on each axis. The complete bounding volume plus a two-block margin must be known; clipped geodes are excluded. Bud attachment direction is checked against its supporting budding block. Ordinary stone/dirt texture orientation is not a stored rotation and is never used.

## History and lifecycle

The in-memory scan session is scoped to the current server and dimension. Chunk coordinates and first observation timestamps accompany immutable classified terrain. Growth evidence uses the first received valid cells. Later observations fill only previously unknown cells; they cannot replace old ages or crystal stages. Replacement/route evidence uses current observations.

Respawning preserves first observations in the same world. Disconnect, dimension change, an explicit module disable, or a scan-floor change starts a fresh session. Unloading discards current terrain but retains first observations while within the history budget. If an old baseline was evicted, revisiting that chunk does **not** silently establish an older-growth baseline: growth evidence stays unknown for that chunk until an explicit new session.

Block-update packets are coalesced by chunk and invalidate affected centers. In-flight results bind all nine input versions, including absent neighbors. Stale results are discarded after updates, unloads and memory eviction.

## Work and memory limits

- World/block access occurs only on the client thread. The worker receives immutable primitive terrain and no Minecraft world/entity/chunk objects.
- Capture is incremental: at most 8,192 block visits with a one-millisecond loop deadline per tick; empty sections are skipped. Surface heights are derived during that same above-floor scan with Minecraft's no-leaves heightmap predicate.
- One cancellable worker and one outstanding result; no accumulating executor queue. Cached chunk summaries are reused for overlapping windows.
- Geometry advances in at most 512 bounded work units or 0.5 ms per slice, then yields. A unit includes a bounded number of neighbor probes; it is not one raw block lookup.
- Open shafts stop at 4,096 visited nodes per candidate; filled routes at 1,024. Exhausted candidates are inconclusive, never qualifying evidence.
- Geodes: at most 16 connected components per window and 4,096 visited cells per component. Candidate overflow makes the affected summary incomplete.
- At most 8,192 packed candidate positions per chunk: four bytes each, rather than one retained Java object per block. Mostly uninteresting dirt interiors are omitted as seeds; connected-feature traversal still examines their geometry.
- Classified snapshot storage is capped at 64 MiB and 512 live/first entries. First-seen identities are capped at 16,384; new growth baselines stop when that limit is reached. A maximum of 512 results is retained.

## Verification and limitations

The focused Java fixtures exercise the production analysis, geometry and snapshot cache against deterministic positive, negative, natural-generation and incomplete-data scenes. The full Minecraft runtime build is a separate gate. Neither establishes live-server detection accuracy or FPS; no actual in-game test has been performed for this release.

Continuous edits can keep a chunk's incremental capture inconclusive; it waits for a consistent capture instead of scoring mixed versions. First capture itself takes time, so some local ticking before observation is unavoidable. Complete natural geodes can still have unusual crystal distributions. Natural vine/kelp growth and open caves do not identify who caused updates or prove that a base exists. Structure-material exclusions are conservative contextual heuristics, not authoritative server structure metadata. Perfectly restored block types/states leave no recoverable placement history.

The checked vanilla 1.21.11 assumptions and bytecode evidence are recorded in `suspicious-chunk-finder-vanilla-evidence.md`. Recheck them for a Minecraft version change.
