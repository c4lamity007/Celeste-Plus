# Celeste+ armor bars and Chams equipment

Release: `celeste-plus-20260929-armor-chams-04`.

## Changes

- **Armor HUD:** thin durability bars replace the numbers. Equipped armor and bars form one compact, vertically centered group; empty slots stay centered. Healthy armor uses a soft yellow-green bar, then amber and red as durability drops.
- **Chams:** **Include armor** adds worn armor and items held in either hand to the effect. It is off by default. The effect uses the rendered equipment shape, including texture cutouts.

## Protection

The payload retains the exact approved VMProtect-protected authentication DLLs from the previous release. They virtualize **16 selected methods across three authentication classes**: eight in AuthServiceClient, three in DpopProofSigner, and five in AuthBootstrap. Auth source and those protected native bytes are unchanged; this release did **not** compile new VMP binaries.

The new visual classes receive the existing Java protection. The final payload passed all **47 protected authentication protocol/lifecycle checks** and all **15 Java continuation stages**. These checks establish compatibility and the recorded protection evidence, not that reverse engineering is impossible.

Code Virtualizer was assessed but was not added: the supplied installation matches its limited demo, and the existing VMP protection remains active. Code Virtualizer supports a command-line interface, so an MCP is unnecessary. No paid purchase is required for this release. The full x86/x64 license is currently [€199 Developer or €399 Company](https://www.oreans.com/CodeVirtualizer.php).

The backend, signing keys, database schema, native policy and public loader are unchanged. The rollout adds this catalog and updates only the Celeste+ catalog selector. Earlier release files are preserved.

## Verification

- Source build passed. The media-complete release has identical code to the tested build and includes both approved Windows media DLLs.
- Nine focused equipment geometry/texture/pose fixture cases passed. The Armor HUD preview is a software simulation.
- All 47 protected auth checks and 15 Java stages passed on this candidate.
- The live catalog hashes, signed loader manifest and loader download hash passed; all five unauthenticated game-download probes were denied.

**Not verified in game:** the user is testing the Minecraft appearance and behavior. The simulation and fixtures do not establish in-game rendering or performance.

The existing **Celeste+Setup.exe** remains compatible. Close Minecraft and the full loader, then reopen Setup and load normally to receive the update.

Windows **Unknown publisher** remains accepted. Server release signatures are separate from Windows Authenticode signing.

## Release identity

- Source baseline: `b1897894a83515b0e71730108532d9d913f7c964`
- Raw runtime: `0a6295865296942ee2d39e76771273dd341b16735a716d4f883fe8560f5ca22a`
- Protected input: `0a6295865296942ee2d39e76771273dd341b16735a716d4f883fe8560f5ca22a`
- Protected payload: `30f30f0ff1c0170aac178b34aca5f60f6eb808a148e3267aa8ab33549ac71687`
- Protection result: `fd4c8f87811e86633a8dad50e731b3c4479280a5235cbfc8cb6fa2b36b8b52b4`
- Live catalog verification: `1693c24d8c951e2ca8d796254a3fea69f78a432721d94d3c6aa5b70fc42e47d8`
- Deployed image: `sha256:9e0caf73a3d80ded2777383bdd05255167026f3c1543920849daade0ce4c44c7`
