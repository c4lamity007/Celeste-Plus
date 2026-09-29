# Celeste+ ambient terrain without scanning

Release: `celeste-plus-20260928-visuals-08`.

## Use

Enable **Visuals > Ground Shaders** for the ambient terrain treatment. Its simple controls are Intensity (35% by default) and Color; the original terrain texture stays visible. This release keeps the ambient look while removing the terrain search and its delay. The existing loader works.

Atmosphere's Basic presets supply the color. Advanced or Atmosphere off uses Ground Shaders' saved Color (blue/cyan by default); switching presets preserves that setting.

The ambient tint covers all visible terrain faces, including elevated tops, stair sides and overhang undersides. It preserves terrain textures and lighting and fades with native fog. Minecraft's rendered terrain surface determines coverage; there is no walking-route search, block scan, player-height cutoff or reachable-surface mask.

The pass renders before entities and leaves terrain depth and framebuffer alpha unchanged. Shader packs and their shadow passes opt out. Disabling the module, changing worlds and disconnecting clear frame/resource state.

## Verification

- Build, Atmosphere regression, Ground Shaders lifecycle and shader GPU checks passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 88 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Check the ambient appearance while moving, including elevated terrain, walls, stairs, overhangs, fog, module toggles and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `71baaa8ffcc74a9001f1b6f2219751d9d1fce02ae6aedfe5f211d542de962d08`
- Protected payload: `fc0335b3be879b75f69d262ef43dff61b919dfbe522c748d5f52bb9f9d177603`
- Catalog: `4c612bb1d29dd1a5239a36cc8019dcfa212a9eaedb8b2f01d95553de46e718cb`
- QA run: `20260929T025730Z-aa4b9b81`; receipt `e538a428852b9c9e9befcf20c60d35f2b80a47ee08b8be5ecc7449cb34308133`
- Protection result: `0a49af8e78fc577d3476bb47de195a5b4eb014e516bccc87e410d46ca4ce9808`
- Live receipt: `644d01296f5c2ff8829cf0457d5013728ee3277258b20f603d2a2021d19afc49`
- Live image: `sha256:69cf42d170785ffc04135bb89af794b75485fef6712828ed2c73483498bd49a9`
