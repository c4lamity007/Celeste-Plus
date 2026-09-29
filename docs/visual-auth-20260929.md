# Celeste+ hit visuals and authentication

Release: `celeste-plus-20260929-auth-visuals-03`. The Windows payload is live and its catalog hashes and public loader checks have passed.

## What changed

- **Displacement** uses the recorded hit position and Minecraft's complete camera transform. The effect follows that local point as the target moves. First-person, third-person and silent-look cases are covered; a missed ray no longer invents a hit at the target's center.
- **Ground hit effects** keep previous waves running. Rapid hits add independent waves, and overlapping highlights do not restart an earlier wave. Existing block-grid paths and compass headings are retained.
- **Ambient Tron Trails** have centered, pointed ends and a short fade when enabled. Paths keep their flat geometry and right-angle turns.
- **Chams Glow** respects transparent hat and clothing texels. The contour and glow follow the visible posed skin instead of filling transparent outer layers.

## Authentication and protection

Java credential loading and enrollment use mutable application-owned buffers. Temporary encodings are wiped, and credentials/signers are closed on replacement, failure and shutdown. The existing protected credential format remains compatible. JCA providers can retain their own copies; this reduces application retention without claiming complete heap erasure.

Private credential helpers receive short implementation names. Public loader interfaces and existing native entrypoints remain stable. No new JNI layer was added. The three authentication DLLs were rebuilt and protected with VMProtect; all 16 selected entries received fresh, byte-bound transformation review. This review establishes the selected entry-to-dispatch paths, not a proof that the binary cannot be reverse engineered.

The backend now bounds concurrent admin-password verification, reserves sign-in attempts before expensive checks, and retains denial audits without committing rejected authentication changes. Catalog metadata uses a bounded cache with file-change checks; licence and proof checks remain active. Optional restricted secret-file settings are available. The backend rollout preserved loader files, native registration, signing keys and deployment configuration. See [server hardening](../auth-service/SERVER-HARDENING.md) and [rollout details](server-hardening-rollout-20260929.md).

## Verification

- Fresh combined runtime build passed with the release's explicit media DLL inputs.
- Backend: 546 passed, three skipped. Client protocol: 933 checks. Credential handling: 54 checks. Runtime signer: 18 checks. Source authentication lifecycle: 44 scenarios.
- Focused displacement, independent ground waves, ambient Tron, and Chams checks passed, including GPU fixtures using production code and shaders.
- Fresh protected-native verification passed all 47 protocol/credential/lifecycle invocations. The native review checker passed its nine negative tests; all 15 Java continuation steps passed.
- The packaged Windows media bridge passed direct cleanup, shutdown-hook and reload checks in owned JVMs.
- The deployed catalog's artifact bytes were hashed, public loader download/signatures verified, and all five anonymous game-download probes denied access.

**Not verified in game:** the user is handling the Minecraft check. Synthetic GPU fixtures do not establish in-game appearance or performance. Earlier fixture-only failures are retained in local evidence; the final passing run uses corrected synthetic certificate timing and Fabric stub ABI.

The existing **Celeste+Setup.exe** remains the download entrypoint. Close Minecraft and the full loader, reopen Setup, and load normally to receive this release.

Windows **Unknown publisher** remains accepted. The executable is Authenticode unsigned; server release signatures are separate from Windows publisher signing.

## Release identity

- Source baseline: `e6c6678caac993481aa837843bb4f68b17d615fc`
- Raw runtime: `1bdcf9ac0d774c4a19cf3ebc7b694c095568cd4a95f6609d990c38fce410e181`
- Overlay manifest: `9fa1448675b8e4ee8110a7a292e8e9246b5f0799a65bed9c9193e65efac06835`
- Fresh build QA: `20260929T213742Z-d4841ab4`; runtime remains blocked for user testing.
- Protected payload: `771fdb4d534faa5ea6dd92030bce86a589dbe4d816409fb4e3f3e0c32b2aeee1`
- Protection result: `1e71373af62b865ed6189142dc8e7b60f3527b27dc9dce529de89fbef7aac2f3`
- Native checkpoint: `7db9ae0ad99a0709655d80536cfc64196ac1875be836987d4d7afc623437d786`
- Live catalog verification: `4ca451b5a508bcef0bb9ae95c24a73f6090486375ab8ad5468345a02dc3f532b`
- Deployed image: `sha256:d80030b31c654c97b0ba9f22da354863fbb085cc71e773dedeb0181b9bb2fcb2`
