# Celeste+ visual refresh

## Changes

- Effect expiry now uses Dynamic Island for two seconds with the vanilla effect icon and red disabled-state text. Early removal, refreshed effects, milk, death, and world changes do not create false expiry notices.
- Removed standalone ReBuff Notifier and Damage Render. Package-only source replacements suppress their classes from the pinned core.
- Idle Dynamic Island is twice its previous desired width, bounded by the viewport, with Celeste centered.
- Storage ESP uses loaded block entities, bounded fallback scans, actual block outline bounds, and joined double chests. Chunk-boundary movement no longer starves queued scans.
- Target ESP uses the exact entity hitbox for Box. Crystals, Cubes, and Sparks use several small orbiting objects.
- Chams has Pearl, Flow, and Mist surfaces with one silhouette alpha composite and unchanged terrain depth.
- Jump Circles has Glow and Lean rings, grow/hold/fade timing, and optional shrinking.
- Swing Animations offers Smooth, Slice, Sweep, Thrust, and Spin. Affect position adds movement after Hand Models placement. Item use, maps, spyglass, and riptide retain vanilla handling.
- Trails adds a soft, fading movement trail. It clears on teleport/world/player changes and freezes during local pauses.
- Trajectories adds Path style: Normal / Glow. Projectile simulation and observed paths of other players are unchanged.
- First-use practice now uses Fullbright at the top of Visuals because ReBuff Notifier was removed.

The new shaders and animation math are original implementations informed by the supplied visual references. No reference branding or copied assets were introduced.

## Verification

The reconstructed Plus runtime compiled successfully with Java 21 against Minecraft 1.21.11. The module QA build and visual shader suite passed. The built JAR contains the added features/shaders and neither removed feature class.

Focused checks passed for effect expiry/queue timing, storage scheduling, trail lifetime/reset/pause, trajectory geometry, exact Target ESP bounds, jump timing, and swing placement. The three Chams materials and both jump materials compiled and passed direct GPU rendering checks on the local AMD GPU. These GPU images are synthetic fixtures, not Minecraft screenshots.

Source fingerprint: `b5d2f7503c3fc3cc8ba56dfbacf75fb37a1f293c7896738907304fe8a12fe17c`.

Development JAR SHA-256: `7fd54e01df75f376086500677bf93725d57a50ee78e5e52a05f2965d10b634a9`.

Both Windows media DLLs are supplied through the existing explicit build override and retain their previously reviewed bytes. The initial development artifact omitted the cleanup DLL; the corrected build includes it and passed the combined suite again.

## Reproducing local checks

Prepare the pinned runtime with `tools/prepare_runtime.py` into a fresh checkout's `build/runtime-core`. Run the Plus `tools/qa/module_qa.py` with `--project` pointing to that prepared runtime and `--suite build` or `--suite visuals`; those two suites are distributed here. Pass Java 21 with `--java-home`. For Windows release inputs, pass the official media override with `--gradle-init-script` and both explicit media DLL environment inputs. The shader checks use the selected runtime's Minecraft dependency cache. `run-visual-refresh.ps1` checks swing, jump, and Target ESP math; `run-chams-material.ps1` checks the three new Chams materials on the GPU.

This is a development artifact, not a protected release candidate. No launcher, auth, or production delivery change is included.

## Remaining game check

The normal Celeste+ loader fetches the single live catalog. The existing Celeste Owner Beta QA profile has no separate staged catalog, so it currently loads the previous release. Actual Minecraft motion, GUI interaction, and screenshot verification require a supported staged delivery route or a separately authorized rollout of these changes. Authentication must remain enabled.
