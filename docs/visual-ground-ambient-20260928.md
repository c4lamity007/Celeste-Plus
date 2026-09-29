# Celeste+ ground atmosphere

Release: `celeste-plus-20260928-visuals-07`.

## Use

Enable **Visuals > Ground Shaders** for the revised ambient ground treatment. Its simple controls are Intensity (35% by default) and Color; the original terrain texture stays visible. This release corrects the ground effect without changing the loader.

Atmosphere's Basic presets supply the color. Advanced or Atmosphere off uses Ground Shaders' saved Color (blue/cyan by default); switching presets preserves that setting.

The ambient tint covers visible terrain faces, including stair sides and overhang undersides. It preserves the terrain's texture and lighting and fades with native fog. Elevated tops receive the tint only when a nearby collision-checked route from the player's feet permits walking, stepping or a normal jump up; headroom and individual slab/stair heights are checked.

Route sampling is incremental and conservative within 32 blocks horizontally and 24 vertically. Unknown elevated tops stay untinted. The check does not assume swimming, climbing scaffolding or jumps across missing columns. Server block updates invalidate affected routes; nearby client predictions and loaded-chunk state are also rechecked. This route check controls appearance only.

The pass renders before entities and leaves terrain depth and framebuffer alpha unchanged. Shader packs and their shadow passes opt out. Disabling the module, changing worlds and disconnecting clear frame/resource state.

## Verification

- Build, Atmosphere regression, Ground Shaders lifecycle and shader GPU checks passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 83 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Check the ambient appearance while walking and looking around, plus slabs/stairs, fog, module toggles and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `7817187f630a18f1482c02007951c688cc2d867d7ab8d118a2714d2630f933ca`
- Protected payload: `45576cb2b962783b0fed736a2df032444885f44386b7b543dcd4d2cd3d07d8f6`
- Catalog: `c9c7b571c96bc311cca56ca14d24de55608249c08d6e2af1e14bece86d5cf884`
- QA run: `20260929T023808Z-25d7255a`; receipt `810f937d3e1968207b6e5cbdf283e5188dabd2b12a44937fc746e2fe666f8e74`
- Protection result: `29e8f5b8226372e92854e3c68a8e6d080a9361abdc868fda6d30b24b97e5c45d`
- Live receipt: `df7e980159299028082531bb0b164f2b2c36866e0953320f611152d7e4cae407`
- Live image: `sha256:67daedf98dc8ead2aa11a2feaa9566772cf1e4ac382cf553f3ec2a61158ab7fe`
