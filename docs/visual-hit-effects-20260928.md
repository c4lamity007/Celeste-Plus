# Celeste+ hit effects and ambient Tron trails

Release: `celeste-plus-20260928-visuals-09`.

## Use

Enable **Visuals > Hit Effects** and select Particles, Ground Effect or Displacement. Particles create a small burst around the hit target. Ground Effect sends glowing, right-angle trails along nearby block seams. Displacement creates a brief expanding lens around the hit cursor, then fades and contracts. The effect is visual only.

For floating paths, choose **Ambient Effects > Style > Tron Trails**. Independent lines move along the X and Z axes at varied heights, turning once per block and leaving fading tails. Density controls the number of paths and Size controls their width. Air checks have a fixed budget; there is no terrain search.

The new effects use the shared Atmosphere palette. Existing ambient styles, terrain tint and Sky Lines stay available. Disabling an effect, changing worlds or disconnecting clears its animation state.

The HUD font atlas now includes 1,181 glyphs instead of 304. Existing glyph metrics are preserved, and display text is normalized to NFC so supported accented and combining text renders consistently.

## Verification

- Build, Atmosphere regression, Unicode font coverage, ambient Tron behavior/GPU, Hit Effects and displacement behavior/GPU/lifecycle checks passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 93 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Check each hit mode in motion, floor trails on stairs/slopes, airborne trails with fog and obstacles, font labels, module toggles and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `35ab4d6bd792a37dd898ebd6467cb84324e9fc82b88c570121930b042cc6a001`
- Protected payload: `d94ae02d71dbfda130769f7a1c8423a0935fc5f03fa8436a999a97327f78cba8`
- Catalog: `d642a426b8c9d36e02ff2e87f9e472e6daf868b8ff57750fcfa6f51a6b5315ee`
- QA run: `20260929T040925Z-c9bcd0bd`; receipt `85beb7aa9dbdfbd1d8b82cd164951bae32fc1928c1d7dde51f3631147afe0d57`
- Protection result: `7f313d5c173e9e5e00c4d75080cbeaa02b0dcb3de05d913916480de25013c155`
- Live receipt: `cd9e516f432d1bfedd81673cdc788e2f3c16017e3232c6a537d4f3d9e32e3692`
- Live image: `sha256:41a923ee9009f6669d33860d29ff1ca99bba536a572613abccda383271cd7080`
