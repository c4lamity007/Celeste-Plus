# Celeste+ protection run — September 27, 2026

This records local execution for the Celeste-Plus repository. Source publication
and production deployment have different completion states. No production
artifact or backend was changed by this run.

## Completed

| Work | Observed result |
| --- | --- |
| Previous source revision `ccd299ae57993c4780c48250e860d7cfae0f4363` | All five jobs passed in [platform CI run 36300930124](https://github.com/c4lamity007/Celeste-Plus/actions/runs/36300930124), covering four native platforms and auth/release tests. |
| Remapped game runtime | Fresh `:remapJar` completed successfully against the prepared pinned core and Plus overlay. |
| Java protection | Final run `build/module-qa/java-protected-candidate-20260927-03` passed all 18 stages. See [exact Java evidence](java-protection-run-20260927.md). |
| Java compatibility | 783 retained payload classes passed declaration loading with `-Xverify:all`; 111 helper checks and protected A/B artifact checks passed. This is not a Minecraft session. |
| Java runner guards | Nine offline tests passed, covering producer pinning, dependency paths, fresh-output preservation, profile boundaries and failed-stage receipts. |
| Native launcher | Fresh Rust/MSVC release candidate and matching private MAP built successfully with `-MapForProtection`. |
| Native metadata | Independent static metadata check passed after stripping. No prohibited local paths or debug entries were found. |

## Native input

The executed build command, from the repository root, was:

```powershell
pwsh -NoProfile -File tools/packaging/build-windows-native-candidate.ps1 `
  -Product celeste_plus `
  -OutputDirectory "$PWD/build/protection-20260927/native-input" `
  -MapForProtection
```

This output directory now exists; use a **new directory** for a later run.

| Item | Identity |
| --- | --- |
| Compiler | `rustc 1.97.1 (8bab26f4f 2026-07-14)` |
| Target | `x86_64-pc-windows-msvc` |
| Candidate | `build/protection-20260927/native-input/Celeste+.exe` |
| Size | 7,346,176 bytes |
| Candidate SHA-256 | `a16e5bd56aba5f8365f64b9bb11e6ac086497077c220cc680c54203a776a1f0a` |
| Private MAP SHA-256 | `6429c7b75ac9a29c7aae409917c1c1f85d6f01652e4fb511c175bfe43cd5bebc` |
| Candidate record | `build/protection-20260927/native-input/candidate.json` |
| Metadata report | `build/protection-20260927/native-input/metadata.json` |

The private MAP contains separately emitted functions for native manifest
verification, token verification, DPoP binding, delivery manifest/installer
verification and attach endpoint/inspection validation. Their presence enables
selection; it does not prove that VMP protected them. No build addresses or
private MAP files are checked into Git.

## VMProtect attempt

The installed GUI reports **VMProtect Lite 3.10.4, registered**. Its executable
SHA-256 is `138d140584e3d703c3592cc3cd15124b428daee75629e25710b65228103ff817`.
The fresh Celeste+ input opened successfully. Import/resource protection were
turned off while configuring the baseline. Packing remained on; no functions
were selected and no successful compile was observed before the GUI repeatedly
became unresponsive. Windows reported the project as **Not Responding** even
after recovery. GUI input was stopped to preserve the project state.

Therefore this run has **no protected native output, native performance result,
or protection coverage approval**. The open, unsaved project is incomplete and
must not be treated as the recommended preset. Resume with the
[runbook](../tools/packaging/README.md) and [target settings](protection-settings.md),
review the saved project, then compile and verify it.

The local `vmprotect-control` plugin documentation was read. Its manifest
contributes MCP tools and no `SKILL.md`. Although its tools were not exposed in
the chat's tool list, its installed MCP smoke client successfully connected,
listed all ten tools, and called `vmp_status`. The installation hash matched;
headless compilation is unsupported. The configured allowed roots exclude the
new Plus build directory, so adding that specific input directory requires the
user's permission before the plugin can prepare the candidate. No trust setting
was changed by the status check.

## Remaining production prerequisites

The readiness inspection found:

- No code-signing certificate with a code-signing EKU in the current-user or
  local-machine certificate stores; no release secrets in the Plus GitHub repo.
- `approved_protectors` remains empty in the native protection policy. No genuine
  Celeste coverage verifier, approved adapter or final signed receipt exists.
- The live `/v1/client-manifest` endpoint returned HTTP 404.
- The service's configured secret names did not include
  `CELESTE_NATIVE_RELEASE_PUBLIC_KEY`.

The new nonce/approval protocol requires a new loader and a coordinated backend
rollout. A server-only update can reject existing clients. The native signing
helper also cannot adopt arbitrary manual Lite output: its Prepare/Sign flow
requires its own approved adapter and `protected-candidates.json`.

Finish native protection and runtime verification, supply the genuine signing
and publication setup, then stage the compatible service and matched client
release together. The release gates remain enforced. The completed local Java
artifacts and unsigned native input are development candidates only.
