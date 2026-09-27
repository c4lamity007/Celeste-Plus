# Celeste+ — compact black HUD

Published release: `celeste-plus-20260921-island-02`.

- All HUD panels, including the projected Target HUD and editor panels, share the Dynamic Island's flat near-black background (`#07070A`, alpha 239). No HUD glass tint, gradient, or scene-blur capture. Array List retains square stepped rows and its text gradient/glint.
- Dynamic Island is narrower with less padding. Its circular outline erases clockwise over one second. Lyrics use bounded monotonic playback estimation to avoid whole-second poll backtracking; deliberate large seeks/replays still reset timing.
- Single-click the island in chat, the HUD editor, or Celeste's menu to play/pause; double-click to skip. A 300 ms click window prevents a double-click from toggling playback first. Pending clicks cancel on screen/track change, disable, or unload.
- Media HUD is a compact movable black card with artwork, left-aligned title/artist, progress and controls. Island remains fixed top-center.
- Armor has icons and remaining-durability text, no durability bars. Horizontal padding is even. Watermark sizing uses its actual rendered fonts.
- Original particle-style Smoke restored; Flame retained.

## Checks and delivery

All eight local checks passed: build, HUD layout/surface, text shader, island timeline/clicks, playback clock, circular shader, HTTPS clock, and hand shaders.
Final receipt: `build/module-qa/20260921T231506Z-cb36194a/result.json`.
Source JAR SHA-256: `f8594f29885f168441d57cb41d8779c002f486f3bf344f682bee924b0b06a1c8`.

Existing protection flow completed: obfuscated payload and two bootstraps, two-part payload delivery, provenance/boundary checks, protected DPoP validation, and disposable auth smoke checks. Fly deployment succeeded; post-deploy verification matched all 13 artifacts. Standard Celeste remains `celeste-20260920-dpop-02`. Launcher identity, auth implementation, database/cloud paths, and DPoP replay table are unchanged. No license or cloud-config reset was performed.

No current-build in-game verification is claimed: there was no Minecraft window or established local QA world. GPU checks are offscreen fixtures, not gameplay evidence.

Logs: `build/module-qa/island-black-release-20260921/`.
Restart Minecraft and use the existing Celeste+ launcher for the update; no replacement launcher is required.
