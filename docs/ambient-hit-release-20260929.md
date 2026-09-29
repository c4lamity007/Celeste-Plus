# Celeste+ ambient colors and hit effects

Release: `celeste-plus-20260929-visuals-02`.

## Use

Open **Visuals > Ambient Effects > Edit effects**, then use **+ Add** to combine effects. Left-click an effect in the picker to add it; right-click an active effect or use **-** to remove it. Select an active effect to change its Density and Size. The cards and Add control use the same highlights as the other pickers.

Each effect has its own **Color** when Atmosphere is off or set to Advanced. Atmosphere presets coordinate all selected effects, including leaves; individual colors are retained for later use. Saved and imported configurations preserve each effect's settings. Fireflies, snow, leaves, crystal motes, tesseracts and Tron trails can share the same scene; Sky Lines remains separately configurable. Ambient styles at 100% Density now produce three times as many particles or trails, within the shared rendering budgets.

**Hit Effects > Ground Effect** sends nine paths outward in eight possible headings. Paths wander with short sideways and backward detours while progressing toward their chosen heading. Each segment stays aligned with the block grid. A path never retraces its own edges; perpendicular crossings are allowed. Separate paths can share segments, with a brighter glow where they overlap.

**Displacement** appears at the hit on the target and follows that point as the target moves. Its screen position uses the active camera projection, including field of view and view motion. Disabling an effect, changing worlds and disconnecting clear its state.

The Chams glow rim follows the entity silhouette with pixel-aligned neighbor samples. It keeps the surrounding halo and the same eight rim samples.

## Verification

- Build, Ambient Effects settings/migration and runtime checks, ambient Tron and tesseract regressions, Atmosphere, hit-ground state and GPU checks, displacement world/projection/lifecycle checks, and the Chams glow regression passed. Every additional check listed in the linked QA run also passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 108 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. The showcase uses the actual effect code in a synthetic scene; it is not Minecraft footage. In-game appearance and performance remain unmeasured. Check the effect colors, right-click removal and saved/imported settings, ground paths on uneven terrain, and displacement while targets or the camera move. Also check fog, obstacles, disabling effects and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `870399e5bd778c811ed5f89ba988f45c26afa6c71ae4e22f4864c4b9256654a8`
- Protected payload: `5b80fb72b13ff4b323288f89f9a13da61c9ac76334a4df8e61ec1b2bb1e4ee99`
- Catalog: `b43a86b9f8f92582286c3125c25fc72d2e6c2a7f5ce13a4e6fd01a445fb3f456`
- QA run: `20260929T200342Z-c6697308`; receipt `3f6878289f111ec1625c7d5d858c77981c437db4861b845230a6dacfeec5cf70`
- Protection result: `91319a9314037357cea1e605eab5dffc2ccbe983d7e15d6ba96f87c05c251dd7`
- Live receipt: `215dba918b4c5d6f2d555fec6860fa02ed6efa6ea92f96669eb47dbfb9965d60`
- Live image: `sha256:0774e344e9e5dfda5cda669c5e7f90c62e7811a77210a7a429b9a51d0a1a76d9`
