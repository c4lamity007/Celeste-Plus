# Celeste+ motion and Jump Circle restoration — 2026-09-28

Release: `celeste-plus-20260928-visuals-03`.

## Changes

- **Jump Circles:** Glow and Lean remain available. Classic Glow restores the original glow, and Splash restores the original 3D water waves. Existing Glow, Lean and Splash settings remain valid.
- **Swing Animations:** Affect position now applies the full reference pose at the existing model-submission hook, after vanilla's pose. Slice, Sweep and Thrust change the resting sword pose as well. Hand Models placement remains underneath. Item use and special item animations keep their own behavior.
- **Hand Shaders / Flame:** a connected plume moves sideways and upward, with a short delayed exterior behind the hand. The current item texture stays unchanged. Smoke retains identical tested history and composite output.
- **Ambient Effects / Tesseracts:** small nearby particles use random positions and lifetimes, straight drift and smooth fades. They have translucent faces, nested cubes, center diagonals and glowing edges. The former Custom Sky cube option is removed.
- **Target ESP:** the 18 rotating objects span the body through the area above the head, filling the former gap. Their tips/corners point toward the target center, including the elevated objects. Box is unchanged.

## Verification

- Combined build and all ten world-effect GPU materials passed.
- Swing integration: 2,739 checks through the transformed host, bridge, real feature/settings code and model-submission hook. The previous version reproduces the idle-pose failure in the same fixture.
- Flame: 495 temporal GPU checks with the actual blur/history/composite shaders, moving item masks and production RGBA8 history. Visible lateral motion, short lag at 30/60/144 fps, unchanged Smoke, exact item pixels and reset checks passed.
- Ambient tesseract geometry, lifetimes, depth occlusion and reset checks passed. Target geometry and restored Jump Circle lifecycle/3D-water checks passed.
- Protection: 41 native checks and 15 Java stages passed. ABI verification covered 67 classes and 1,370 members. All 51 raw auth entries, both media DLLs and 953 retained protected entries are unchanged.
- Delivery: 82 backend fixtures passed; one expected Windows symlink-privilege case was skipped.
- Live rollout verified all 63 game artifact files and all 28 backend source hashes. Native policy sequence 3, Standard, public loader and schema remain unchanged. Five anonymous game-download routes still deny access.

**Not verified in game:** the user chose to test the published update themselves. GPU previews and synthetic renderer fixtures are not Minecraft screenshots or gameplay verification.

Keep using the existing Celeste+Setup.exe. Fully restart Minecraft and load again through the normal authenticated flow to receive this update. No new loader is required for these visual changes.

## Release identity

| Item | SHA-256 |
|---|---|
| Raw runtime | `d79aa5e22dc9c154bbfcf9bc2283ff25e50fd91e8637a4051f118f5ddeb56758` |
| Protected payload | `f07621c83c8b0df60910513e70a7af0c0b64d7b12e7741a47be6203856c9a4a6` |
| Delivery catalog | `aa4a34c3ee31909985ef6e1f7277f16b4f9599630fc2b5bc7b54d58ef964527b` |
| Runtime catalog identity | `c5623d7807bec44579d2e0e2bc367d141262367292f1bab932bfcd462d5e0743` |
| Protection result | `382f353982eee40fa7823495c837ec6f5a98d8e8899aa8640fb4a3b5af4defd5` |
| Live receipt | `81eead581b82c76bdd750e7770689b818b1d695a30381f9c9f19787d6234bd10` |

Live image: `sha256:38a99e93106aa6079774e24797c11ed58df53dc62806f308bbc8d35c8f01f027`.
