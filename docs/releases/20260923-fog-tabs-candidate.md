# Celeste+ fog, sidebar and tour fixes — published

Release: `celeste-plus-20260923-fog-tabs-01`.
Published and verified September 24, 2026 at 02:39 UTC
(September 23 at 7:39 PM Pacific).

World ESP, chams, glows, trajectories, block markers, jump effects and ambient
visuals now draw after the screen-space fog pass. This prevents close transparent
visuals from inheriting the fog distance of background terrain. Existing world
depth/occlusion and intentional X-ray behavior are retained. Shared callback
ordering remains stable across Plus unload/reinjection.

The GUI remembers the last sidebar content tab across closing, restarting and
reinjection. The HUD editor retains the parent tab. Tour steps and shared
profiles do not overwrite this local preference.

First-use tour cards have no outline, drop shadow or fullscreen dimming.
The existing target cues, skippable steps and first-ever authenticated GUI-open
behavior remain in place.

## Verification

- Build and `visual-fog` suite passed.
- 282 compiled callback, order, owned-batch flush and reinjection assertions.
- Actual fog and sprite GLSL: all five presets, translucent/additive images,
  foreground occlusion, intentional X-ray rendering, disabled identity and
  unchanged scene depth.
- Actual Arrow 2 and 3 PNGs plus `texture_rect` GLSL: correct per-pixel color and
  transparency over normal/dense fog and sky. Their existing HUD render path
  already follows fog; no Arrow ESP behavior was changed.
- 113 sidebar enum, invalid-value, tour-isolation and disk-persistence checks.
- Protected tour classes passed 153 behavior and 48 layout assertions, with all
  seven production class origins checked under `-Xverify:all`.
- Obfuscation, split payload validation, protected bootstrap verification and
  disposable authentication smoke checks passed.
- Live service health, all 13 deployed artifact hashes and both authenticated
  Plus payload endpoints verified. Unauthenticated requests return HTTP 401.

Final QA receipt: `build/module-qa/20260924T023244Z-02cebf7a/result.json`.
Sidebar receipt: `build/module-qa/fog-tabs-preflight-20260923/sidebar/behavior.log`.
Release receipt: `build/module-qa/fog-tabs-release-20260923/receipt.json`.
Live verification: `build/module-qa/fog-tabs-release-20260923/live-after.json`.
Simulated video: `docs/previews/celeste-plus-hud-tour-v5.mp4`.

Deployed image:
`registry.fly.io/qb0f90cf11f4d1d52@sha256:8dcaaba60580ce39c9d512ad9fa58868c08a8c7ddc424db94219e9966f45d8ed`.

Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Launcher
identity, authentication implementation, volume mounts and database/cloud paths
are unchanged. Both Plus payloads use the existing pinned authentication origin.

The fog comparison is an offscreen GPU fixture and the tour video is simulated.
No authenticated local-world check of this exact build is claimed.
