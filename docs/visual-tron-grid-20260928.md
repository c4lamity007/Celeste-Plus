# Celeste+ Tron trails on block seams

Release: `celeste-plus-20260928-visuals-10`.

## Use

Choose **Ambient Effects > Style > Tron Trails** for paths aligned to whole-block seams. Each trail stays on a flat XY, XZ or YZ plane, so paths can be horizontal or vertical. Right-angle turns and fixed plane geometry keep the strips from twisting toward the camera.

Enable **Hit Effects > Ground Effect** for nine distinct outward paths along horizontal XZ block seams. They share the four exits from the starting point without drawing an edge twice. Paths stop at unsupported ground or a height change instead of climbing steps or slopes.

Ground hit trails blend from the active theme's primary color toward its accent along each path, with complete one-block sections fading in and out. Ambient Tron keeps its smooth moving heads and tails and current theme tint. Other ambient styles, hit particles, displacement and HUD fonts keep their existing behavior.

## Verification

- Build, Atmosphere regression, ambient Tron and Hit Effects behavior/GPU checks passed. Every additional check listed in the linked QA run also passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 98 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Check straight grid alignment while turning the camera, vertical ambient paths, complete block-section fading, and floor paths at steps and missing supports.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `c25d2ccfa45a0bab1580a4412172e733ddf66df17daeba622d97546acbb224be`
- Protected payload: `126d28575c40afdfb24f1a34f150fc295c3e363309eb56f7a4c5dc7a06f8f064`
- Catalog: `e6de516da1fb5e35e16938fd6de8cc8698c2f0879796f20c926bc63ce4e406ba`
- QA run: `20260929T050207Z-19a40468`; receipt `8760f91a7dee211730b182c18128061b4e2967c34ce15bc27de41e0325a9a6cd`
- Protection result: `97fada9f930acf8c3131f8182f63e204197952028ec926c1789656d388358997`
- Live receipt: `5e3f5ea0fdb6257daef4ca80858c203fa9955c968b2922dd6ed7b48eabf50db9`
- Live image: `sha256:8f641e6cec7f1dce568d60c0c7b1a32ff997736e4bc7b2ee749241225d09b723`
