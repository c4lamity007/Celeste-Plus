# Celeste+ — HUD/world input and visual fixes

Published release: `celeste-plus-20260922-hud-world-input-01`.
Verified live at `2026-09-22T02:38:00Z`.

## Changes

- HUD sizing now uses a fixed physical density and no longer changes with Minecraft GUI scale 2, 3 or 4.
- Dynamic Island uses the cleaner pause state and circular enable/disable indicators.
- Storage ESP and Block ESP scan already-loaded chunk sections with palette prechecks, immediate selection refresh and bounded per-tick work. They do not force chunks to load.
- Item ESP now offers Box, Item Highlight and Both. Item Highlight renders the dropped item's model rather than the removed halo effect.
- Freecam detaches only the local camera: the real player can still move, remains rendered, keeps the live hotbar, and manual attacks/uses originate from the real body and server-directed look.
- Trajectories render the remaining future path of a live projectile and remove the section already travelled.
- Auto Firework removes the misleading delay setting and uses a small duplicate-edge debounce for right-click activation.
- Target HUD restores the earlier glass surface only for the Above Target layout, and Armor HUD uses corrected fixed-density corners.
- Both the normal Fabric visual hook and protected beta visual bridge were rebuilt for the Item ESP model pass.

## Verification

All 10 final local checks passed: build, HUD layout, Dynamic Island, playback, HUD polish, block ESP, Freecam/actions, trajectories, Auto Firework and visual hooks.

Final receipt: `build/module-qa/20260922T021737Z-0d887dba/result.json`.
Source JAR SHA-256: `aeb73745f53b98e5b3d4badd4be8190fe4142620f4b2bb8b7a50570a4996946a`.
Source/QA fingerprint: `7c9cbad43559e182c06cd9ce86abaf568a340dfd2b82dc5cde4e6525f8608bf0`.

The available Minecraft session was an older build on public multiplayer, so it was left untouched. No current-build local-world gameplay smoke test or screenshot is claimed; the receipt records runtime as blocked/partial while every automated check passed.

## Protected delivery

The payload was obfuscated, checked for boundary/provenance integrity, split into `payload-a.bin` and `payload-b.bin`, and assembled with freshly rebuilt and obfuscated Bootstrap A and Bootstrap B. Protected DPoP and disposable authentication smoke checks passed, including rejection of replayed proofs and installer/runtime digest substitution.

Fly app `qb0f90cf11f4d1d52`, machine `85d426c4429798`, is healthy on image:
`sha256:152eab6575bea68e48a6bcd7357459108fbdf8d46c518a8fa4d74c930642ef04`.

Postdeployment verification matched all 13 standard/Plus artifact hashes and confirmed public HTTP health, unchanged volume mounts, installer identity, database/cloud paths, DPoP implementation and replay table. Standard Celeste remains `celeste-20260920-dpop-02`. No licenses, bindings or cloud configs were changed. No GitHub push was performed as part of this Fly release.

Release evidence: `build/module-qa/hud-world-input-release-20260922/`.

**Fully close Minecraft, then use the existing Celeste+ launcher.** The launcher already fetches the current signed release, so no replacement EXE is required.
