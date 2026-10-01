# Suspicious Chunk Finder fixtures

For the complete current suite, including buried-block outlines and the activity estimator:

```powershell
python tools/qa/suspicious-chunk-finder/run_refinement.py `
  --project . `
  --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot' `
  --out build/suspicious-refinement-check-01
```

This runs all three fixture classes against the production sources. It adds actual qualifying block publication, cancellation/exhaustion, growth sample attribution, cross-window deduplication, and conservative candidate-region tests. Sparse crystals is removed; mature geodes remain +15; missing budding or an independently recognized fully stripped geode share one +65 check and a 65-point geode-family cap. The total positive maximum is 170. Radius defaults to seven chunks.

Compile and exercise the **actual** `SuspiciousAnalysis.java`, `SuspiciousGeometry.java`, and `SuspiciousSnapshots.java` against deterministic, fully specified terrain. Java 21 and Python 3 are required. The output directory must be new; the runner snapshots all inputs, records their SHA-256 hashes, and keeps compile/run logs plus `result.json`.

```powershell
python tools/qa/suspicious-chunk-finder/run.py `
  --source runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/suspicious/SuspiciousAnalysis.java `
  --source runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/suspicious/SuspiciousGeometry.java `
  --source runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/suspicious/SuspiciousSnapshots.java `
  --java-home 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot' `
  --out build/suspicious-finder-check-01
```

Coverage includes every score and family cap, toggles and threshold equality, exactly nine chunks, neighbor-only and negative-coordinate evidence, natural-generation controls, sample minimums and ratios, missing/unknown terrain, first-observation growth stability, geode enclosure and crystal direction, source versus flowing water, immutable summaries, cancellation, and buried-route geometry. Shaft scenes cover both 3x1 orientations, X/Z bends, face connectivity, floor configuration, the 80% traversal requirement, liquid/sealed/wide/clipped negatives, shared work slices, and candidate exhaustion. Snapshot fixtures exercise unknown-cell filling, immutable views, unload/revisit and suspension, conservative eviction, byte accounting, the 64 MiB snapshot budget, session-history limits, and reset.

The exhaustion fixture deliberately uses a synthetic column taller than a real Minecraft world so the actual 4,096-node cap is reached deterministically. The work-slice budget counts bounded work units, not raw neighbor lookups. These tests establish core behavior only. They do **not** establish Minecraft collection accuracy, real-world heuristic accuracy, render appearance, FPS, authentication, or release readiness. Full build and game-side lifecycle/render checks remain separate.
