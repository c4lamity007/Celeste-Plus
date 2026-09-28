# Celeste+ ambient tesseract rotation — 2026-09-28

Release: `celeste-plus-20260928-visuals-04`.

Ambient tesseracts now visibly turn on two axes while drifting. Each particle life has independently selected directions and bounded speeds: yaw 0.24–0.48 rad/s and pitch 0.14–0.30 rad/s. The previous signed rates could be almost zero. Rotation stays smooth and pauses with the existing particle clock.

Drift, random births, fades, size, translucent faces and glowing geometry are unchanged. The raw runtime differs from visuals-03 in only `AmbientTesseractField.class`; all other 1,001 runtime entries are identical.

## Verification

- Build passed.
- Particle rotation/lifecycle and real GPU checks passed, including geometry motion independent of drift, varied speeds/directions, invisible respawns, reset, terrain occlusion and unchanged other ambient materials.
- The previous field implementation fails the new visible-rotation regression, reproducing the issue.
- Required protection gates passed: 41 native checks and 15 Java stages. Auth and media DLL inputs remain identical to their approved versions.
- Delivery fixtures: 82 passed; one expected Windows symlink-privilege skip.
- Live verification: all 68 game files, 28 backend source hashes, signed manifests and the unchanged public loader verified. Native policy remains sequence 3; anonymous game downloads remain denied.

**Not verified in game:** the user is handling the in-game check. The GPU preview is synthetic evidence, not a Minecraft screenshot.

Keep the existing Celeste+Setup.exe. Fully restart Minecraft and load again normally to receive the update.

## Release identity

- Raw runtime: `84f533768d8b96b1852ebbc026fe6822b138af911faa685013c6c9468bcc8132`
- Protected payload: `325c37f2ffc2a730844ad6b20d74f9c009b94b2d241a0088493f92f225479452`
- Catalog: `4fd5db28c989f1c940b25b1663f4fbf0d5c3cb5e6eb1d9c157e008000943eb14`
- Runtime catalog identity: `f91fd51073fe1176a3f079e1d8979c750fbec965e4b3cd3d2793cdf4c968ac2f`
- Protection result: `0f0838fbcc2c91e6cac2947bb0105b1e4a658999d3441f809c39c4dff5edce0a`
- Live receipt: `ff08b75da902ac3f022287f18c1ca5b21120d21b50afc3b669a30819d5c7508b`
- Live image: `sha256:7e4c0ca83531e5b8293aa8485f8c82b9d6ff28ce74144134a16d7b5aeb0a2d07`
