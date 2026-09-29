# Celeste+ atmospheres, Flame and tesseracts

Release: `celeste-plus-20260928-visuals-05`.

## Atmosphere

Enable **Visuals > Atmosphere**, then choose Aurora, Nebula, Sunset, Abyss or Black Hole. These palettes take their colors from the existing sky shaders and coordinate enabled sky, fog, hand effects, target markers, trails, chams and ambient accents. Abyss is the default selection; Atmosphere starts disabled to preserve existing setups.

Basic mode hides the color controls it manages. **Advanced** restores each module's saved sky/fog/color controls. Switching presets never overwrites those settings or enables other modules. Geometry and intensity controls remain available. Preset changes refresh per frame, including while the world is paused in the GUI.

## Flame and ambient tesseracts

- Flame has a tighter saturated rim, deeper outer gradient, coherent sideways/upward motion and moving highlights over the item/hand. The original texture and lighting remain readable. Smoke's shader/history output remains unchanged.
- Tesseracts use eight matching outer-to-inner corner links, with no center-crossing diagonals. The transparent fill, glow and rotation remain.
- The particle field spans a 22-block sphere with an outer fade, reaching roughly 20 blocks above, below and around the camera. Each life gets one random direction and speed between 0.14 and 0.38 blocks per second.
- Full rotated bounds must fit inside loaded air. Particles fade near surfaces; the field can extend below the player only through air.
- Up to 192 potential cells are reserved before camera culling and life opacity, with a fading cap boundary. Turning the camera and particle births do not abruptly replace full-opacity cubes. The default open-air fixture had 145 active particles, including 25 more than 10 blocks above and 24 below.

## Verification

- Combined build and all four focused suites passed: actual GUI/settings lifecycle, Flame GPU, tesseract behavior/GPU and fog lifecycle/GPU.
- Preset/Advanced/off preserve saved settings and unrelated module toggles. Hand tint stays stable so it does not reset temporal history every frame.
- Flame checks include fast swings, texture contrast, animated bands, connected aura, 30/60/144 FPS history and exact Smoke comparison.
- Required protection gates passed: all 41 native checks and 15 Java stages. Approved authentication and media DLL inputs retain their hashes.
- Actual-catalog delivery fixtures passed; one expected Windows symlink-privilege skip.
- Live verification checked all 73 game files, 28 backend source hashes, signed manifests, the public loader and five anonymous download denials.

**Not verified in game:** the user is handling the Minecraft check. GPU images and the before/after Flame video are synthetic fixtures, not game footage. In-game appearance and CPU/GPU performance remain unmeasured.

Keep the existing Celeste+Setup.exe. Fully restart Minecraft and load normally to receive the update.

## Release identity

- Raw runtime: `b9d45f0c5cfed0183da3bf041b215a18dee2b095225d8a53e692df8f2fac5dcc`
- Protected payload: `dd8e72ec796c8a2ffc55fa41a90ea0dd61b5efcdba0b4351a75bf942e173f5a8`
- Catalog: `6b5e341897d23d9d0df9bc18fbad4cacbd4b2e06017006d0ca3607b3c7ca9afb`
- Protection result: `31a6f19a6db91e23d5368ffed22bfac8c08ae71752489fb97897ca51125e7598`
- Live receipt: `d60947e4e5b416ed9861d26bfeb1c330b79480da9b470a5a6f2a3502ab55d422`
- Live image: `sha256:7fa9ca464094aa4097e6b049be15df71fd98c41aa65781d03f404486b013b025`
