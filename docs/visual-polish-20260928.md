# Celeste+ visual polish â€” September 28, 2026

Release: `celeste-plus-20260928-visuals-02`

## Changes

- **Trails and trajectory Glow:** round, camera-facing bloom replaces the previous stretched sprites. The bright center fades smoothly, with transparency applied once.
- **Jump Circles:** Glow has a wider, softer ring profile, closer to the supplied reference.
- **Target ESP:** Crystals, Cubes and Sparks each use 18 small objects across three staggered bands. They travel around the target and roll while their tips or corners face its center. Box still matches the exact entity hitbox.
- **Swing Animations:** positional swings move in camera space and work alongside Hand Models placement and rotation.
- **Hand Shaders â€” Flame:** the flame remains attached to the current hand/item silhouette as one continuous effect. Smoke keeps its existing behavior.
- **Sky cubes:** translucent faces, an inner cube and connecting lines give the rotating cubes their tesseract shape. Soft glow surrounds the crisp edges.
- **Chams â€” Glow:** a new material adds a crisp outline and broad, soft bloom outside the posed entity silhouette. It preserves the original body, uses no hitbox border and leaves terrain depth unchanged.

The Chams bloom uses a separable Gaussian blur with half-resolution intermediate
storage. The occupied silhouette is excluded from the final effect, including
overlapping limbs and clothing. Its screen-space spread is bounded and scales
with resolution. Pearl, Flow, Mist and the existing Fake Lag material remain
available with their previous rendering behavior.

Target geometry remains bounded at 144 faces for Crystals/Sparks or 108 for
Cubes. Limited render budgets retain complete objects distributed around the
target. The shared world-effect ceiling is unchanged.

## Launcher cleanup check

The existing `Celeste+Setup.exe` remains compatible; no new loader is required
for this visual update. The user's mini launcher matches the previously
published executable.

Normal cleanup removes the exact downloaded EXE and its empty private folder
after the full loader and its cleanup helpers exit. Closing the loader while
Minecraft remains open can delay deletion: its cleanup helper runs from the
same EXE, and Windows keeps that file mapped until the helper exits. The mini
retries deletion for up to 24 hours.

Cleanup is not guaranteed if Setup is forcibly terminated or the file remains
locked past that bound. This audit does not claim immediate deletion after
every loader-window close. No full-loader, authentication or cleanup-architecture
change is included here.

Ten Windows custody tests passed. A disposable fixture whose parent exited
after 100 ms and whose same-EXE helper remained for 1,600 ms removed its owned
EXE and folder after 2,147 ms. This check did not execute the real loader or
access Minecraft.

## Verification

- Target geometry: 2,451,192 focused checks passed.
- Exterior Chams Glow: 25,212,139 checks passed on the local AMD GPU. The stronger
  halo remains visible 12 pixels outside the fixture silhouette; the previous
  narrow shader fails that visibility check. Occupied body pixels, world depth,
  empty frames and screen edges were checked.
- Mesh and GPU previews are synthetic fixtures, not Minecraft screenshots.
- **Not verified in game.** Desktop control was stopped with Escape before
  in-game checks of this build. No gameplay or appearance claim is based on
  an older loaded release.

Final combined build and world shader suite passed against source fingerprint `ab6010ef41000a9a4cbcd79dbd16441193997d94eec07281a065a92d8f4e5e7b`. Raw JAR: `ca0581981265ad262e4ca58ec2ed4394ba5ba888f5e6edeade719303140c5fa8`. Focused swing checks cover all styles, both hands and Hand Models on/off. The Flame GPU comparison preserves Smoke pixel-for-pixel; sky material checks cover translucent faces, nested edges, center diagonals, glow, smooth fades, all five skies and the disabled state.

The final protected candidate passed all 41 native checks and all 15 Java protection stages. All 51 raw authentication classes, both media DLLs and 956 retained protected entries remain byte-identical. The visual ABI check passed for 62 classes and 1,305 members. Protected result SHA-256: `b799e5a65e75e23e062b5a4138f33e49ccfe9b529d98f9f796c2ffda2aaaf4ff`. Existing native protection was retained; this visual release did not rebuild the loaders or native authentication DLLs.

Production deployment completed. All 82 delivery fixtures passed against the actual five catalog files; one Windows symlink-privilege case was skipped. Live verification checked all 58 game files, unchanged backend/Standard/native policy/loaders, signed public policies and five anonymous game-download denials. Image: `sha256:9aebc2d627ce4255ce034521fa12e5328d31c6608f7358e0833c114c3c52bb33`. Catalog: `971c42afdd3d49e3ec471d201656ba6c28b8e33b6de6c29a63ee4e351343a20f`. Runtime: `57031dc58859c56f91e170f9b6d5d113f8160a36933e2cc56854ad78835606cf`. Source and release notes are published only to [Celeste-Plus](https://github.com/c4lamity007/Celeste-Plus/releases/tag/celeste-plus-20260928-visuals-02).
