# Celeste+ ambient effect layers

Release: `celeste-plus-20260929-visuals-01`.

## Use

Open **Visuals > Ambient Effects > Edit effects**, then use **+ Add** to combine effects. Select an effect to change its Density and Size; use **-** to remove it. Fireflies, snow, leaves, crystal motes, tesseracts and Tron trails can share the same scene. Sky Lines remains independently configurable.

Existing single-style configurations are migrated to the matching effect with their saved settings. Saving and importing a configuration preserves the selected combination and its per-effect values.

The new controls preserve the existing world-space rendering, shared Atmosphere palette, fog handling and terrain occlusion. Disabling an effect, changing worlds and disconnecting clear its animation state. The runtime keeps bounded particle and geometry budgets when several effects are enabled.

## Verification

- Build, Ambient Effects settings/migration and runtime checks, ambient Tron and tesseract regressions, and Atmosphere regression passed. Every additional check listed in the linked QA run also passed.
- All 41 native checks and 15 Java protection stages passed.
- Actual-catalog fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification matched 103 game files and 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU previews are synthetic, not Minecraft screenshots. In-game appearance and performance remain unmeasured. Check multiple selected effects in motion, individual toggles and settings, saved/imported configurations, fog, obstacles and a world change.

Keep the existing **Celeste+Setup.exe**. Fully restart Minecraft and load normally for this update.

## Release identity

- Raw runtime: `021553b870f151d884182ebdc55ba7693f1d43550142adea9eaf9c250e024cfa`
- Protected payload: `923c81b4a124cdc0d3d2e8d83bbd65881d3df3d284caba64f1d0a1a15abd59b7`
- Catalog: `736c945901ce0f1f81974a910a7cc53a5ed5b9e8d450a8e31a8efdbc576b1080`
- QA run: `20260929T161450Z-a2932fa0`; receipt `311114e9d47b6578fd3ef5348c364bf0a593459799253e2cd95940d15e8c6699`
- Protection result: `ade5470205ed726e80ed176d44a90110b2edf6f0b042add6e2952b17ccb75649`
- Live receipt: `efc76b4c2a11703783d7988d882b455dba87acb0ff745ee5cf007c3ef318ea94`
- Live image: `sha256:8e68f8837ead33b156ea3903dd71990a8beaecf4c3fe8b28927422c62fb3e1e3`
