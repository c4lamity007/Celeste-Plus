# Celeste+ protection run — September 27, 2026

This records execution for the Celeste-Plus repository. The full authentication
backend and repaired Java release `celeste-plus-20260927-auth-hardening-02` are
deployed and verified. The protected Windows launcher is registered at native
policy sequence 1. After fully restarting Minecraft and retrying, the user
confirmed: “Loads successfully.” GitHub publication is ready; its release is
still a draft at this record's freeze. [Initial infrastructure evidence](native-infrastructure-rollout-20260927.md)

## Completed

| Work | Observed result |
| --- | --- |
| Previous source revision `ccd299ae57993c4780c48250e860d7cfae0f4363` | All five jobs passed in [platform CI run 36300930124](https://github.com/c4lamity007/Celeste-Plus/actions/runs/36300930124), covering four native platforms and auth/release tests. |
| Remapped game runtime | Fresh `:remapJar` completed successfully against the prepared pinned core and Plus overlay. |
| Java protection | Repaired run `build/module-qa/java-protected-candidate-20260927-04` passed all 18 stages. See [exact Java evidence and the earlier injection regression](java-protection-run-20260927.md). |
| Java compatibility | 783 retained payload classes passed declaration loading with `-Xverify:all`; 111 helper checks passed. The expanded protected A/B fixture passed 442 assertions in each of three fresh JVMs, including the actual host call descriptors. |
| Java runner guards | Nine offline tests passed, covering producer pinning, dependency paths, fresh-output preservation, profile boundaries and failed-stage receipts. |
| Native launcher | Fresh Rust/MSVC release candidate and matching private MAP built successfully with `-MapForProtection`. |
| Native metadata | Independent static metadata check passed after stripping. No prohibited local paths or debug entries were found. |
| Native MAP aliases | Seven same-build function aliases passed the executable/MAP binding checks; the executable bytes were preserved. The alias helper passed 19 tests. |
| VMProtect compilation | Job `1cbaae1f-66ec-4bf2-93dc-6183a52a4c01` completed with all seven resolved aliases selected for Virtualization. This is compiler evidence, not independent transformed-code coverage approval. |
| Exact-artifact coverage | `verify_reviewed_vmprotect.py` verified all seven reviewed entry-to-VM paths against the pinned protected executable and private instruction evidence. It does not attest authenticated attachment or performance. |
| GUI candidate importer | 22 tests passed for the gated import into the normal Sign layout. It requires a genuine approved verifier and receipt before accepting a candidate. |
| Packaging QA | 136 tests passed with one optional private Capstone test skipped; six private coverage tests and seven publication guards passed separately. |
| Initial protected native startup smoke | Before native registration, the VMProtect output rendered the activation screen and refused access when approval was unavailable. That smoke did not activate or Load the game. |
| Protected native metadata | Static checks passed with no debug entries or local-path findings; no Authenticode certificate was present. |
| Native publication authority | A separate Ed25519 identity was created with Windows current-user DPAPI storage and verified by a real encrypt/decrypt round trip. Only its public key was installed on Fly. |
| Initial additive backend rollout | Version 128 preserved all 13 existing artifact files and old auth behavior while adding the native endpoint. It was followed by the coordinated full backend rollout. |
| Final backend and Java repair | Version 130 is healthy (1/1). Deployed source hashes match staging; all 23 artifact files match, including 18 retained files and the five repaired release files. Native policy sequence 1 is unchanged. |
| Actual user retry | The user confirmed successful Load after a full Minecraft restart. This confirms the reported injection error was resolved; no broad gameplay or performance result is inferred. |

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

## VMProtect compilation

The installed GUI reports **VMProtect Lite 3.10.4, registered**. Its executable
SHA-256 is `138d140584e3d703c3592cc3cd15124b428daee75629e25710b65228103ff817`.
Earlier GUI attempts became unresponsive. A subsequent compile using the raw
Rust MAP symbols did not resolve the selected functions; that output was
rejected. The successful run used seven plain aliases initially generated by a
local prototype. The committed `prepare_vmprotect_map.py` subsequently reproduced
the exact derived MAP bytes and independently validated the actual job's mapping
against the input image, exception-function boundaries and original MAP. The
reproducible helper changes MAP labels only; it copies the executable unchanged
and retains the original MAP.
No private symbols, addresses, MAP files or project files are published here.

| Successful compile evidence | Value |
| --- | --- |
| Job | `1cbaae1f-66ec-4bf2-93dc-6183a52a4c01` |
| Derived MAP SHA-256 | `5c7db3e054535b0cb6869e1daea9dc11618756ca46088482ea876faf4f69398a` |
| Output size | 17,586,688 bytes |
| Output SHA-256 | `ad538688e39f7eb1d811379c81a2bd955300984cbd80669f503ccd2623bf89e4` |
| Saved project SHA-256 | `9e069392d6017f2e414bee236a75eec02867a965ed9ce2611550fbb7eef40f35` |
| Compiler evidence SHA-256 | `40f8198aa079ac3e679a1fd12b1303af6690afdc8dd9d107d3281864201eeec5` |
| Selected modes | Seven resolved aliases, all Virtualization; no Ultra |
| Saved GUI options | `332032` |
| Memory / import / resource protection | On / off / on |
| Pack output / strip debug / strip relocations | On / on / off |
| Additional debugger / VM detection | Off / off |
| Complexity | Default 20% |

The numeric options value records this saved GUI template. The bundled help
does not document its serialized bit assignments, so it was not reconstructed
from guessed masks. These are the settings that compiled; no native performance
or complete protection-coverage claim follows from them.

The local `vmprotect-control` documentation and read-only MCP status/project
inspection were used. The installation hash matched. The exact Plus input root
was subsequently approved and configured in its allowed roots. Lite still has
no headless compiler; the actual compile used its GUI workflow. The bridge does
not provide code signing or an independent transformed-code verifier.

The [runbook](../tools/packaging/README.md) contains exact alias preparation,
validation and GUI import commands. The importer rechecks the original input,
source inventory, original/derived MAPs, alias record, selected project and
compiler evidence. A genuine approved verifier must attest every selected
transformation before it can emit `protected-candidates.json` for the normal
Sign path. Unapproved protector/configuration/verifier tuples are refused.

The local evidence copy is under
`build/protection-20260927/native-vmp-candidate/`: `Celeste+.exe`, `project.vmp`,
`original.map`, `derived.map`, `mapping.json`, `input-candidate.json`,
`compile-evidence.json` and `metadata.json`. The GUI state
reported seven resolved selections with Virtualization; its compile log reported
the output size. The automatic Execute prompt was declined. Private evidence
contains build addresses and is excluded from Git. Screenshots are retained at
`build/protection-20260927/vmp-compile-completed.png` and
`build/protection-20260927/protected-loader-startup.png`.

A separate controlled startup of the emitted output displayed the activation
screen and the message: “Connection required to verify this client. Try again
when the verification service is available.” This verifies that startup rendered
and the unavailable-approval path refused access. It is not an activation, game
Load, complete functional test, or performance result. The output passed the
static metadata check with no debug entries or local-path findings and remains
unsigned.

Subsequent read-only review of the actual protected process observed all seven
selected entries reaching VM operand decoding and dispatch. The exact-artifact
verifier now mechanically checks those reviewed instruction paths, the current
source, original input, protected bytes and private evidence index. Its result is
`coverage-verified-for-exact-reviewed-artifact`, with seven virtualized functions.
It is intentionally limited to this reviewed executable, rather than a generic
detector for other VMProtect outputs. Private MAPs and addresses remain excluded
from Git.

The coverage record is `build/protection-20260927/reviewed-coverage.json`, SHA-256
`631067af8f7999c537de9932dc34cd760fad72bb5ab598a5d282057bdcbe3898`.
Its environment-test, authenticated-attachment, performance and publication
approval fields remain false. The report proves the reviewed transformation
paths, not those separate release properties.

## Authorized Windows publisher exception

The user explicitly authorized release of this protected Windows executable
with server verification and accepted Windows showing **Unknown publisher**.
The executable remains **Authenticode unsigned**. The publication authority's
signature on an exact-hash allowlist and the server's signed response do not
create a Windows publisher certificate or suppress that warning.

This is an exception for the reviewed release, not approval of arbitrary unsigned
CI candidates. Exact source/artifact protection evidence, reviewed policy and
receipt, authenticated registration, nonce-bound server verification and the
coordinated client/backend rollout remain required. Registration and the repaired
backend rollout have now passed; GitHub publication remains the final step.

The exception binds release `celeste-plus-20260927-auth-hardening-01` and the
protected output hash recorded above. The strict Prepare/Sign helper is
unchanged; no Sign step is claimed. The authorized path uses the approved exact
receipt and metadata check, then the existing direct registration helper.

The reviewed policy now approves this exact Windows tuple:

| Component | SHA-256 |
| --- | --- |
| VMProtect executable | `138d140584e3d703c3592cc3cd15124b428daee75629e25710b65228103ff817` |
| Saved project | `9e069392d6017f2e414bee236a75eec02867a965ed9ce2611550fbb7eef40f35` |
| Exact-artifact verifier | `5dff22d807a49735c2febfddc62e541770f31c456fa78ef2feb5bf66d9a72c3e` |

The verifier pins the reviewed input/output and private evidence. A different
build or configuration requires a fresh review; this tuple is not blanket
approval of other VMProtect outputs.

## Registration and coordinated deployment

The approved protection receipt is
`build/protection-20260927/approved-release/distribution/celeste_plus/protection-receipt.json`,
SHA-256 `84ae29dd480b2bd88902dcba1c6c55ea1cf2754bb0e8d50df01c53fe757758e4`.
The policy SHA-256 is
`7a1803808f8dc3a23d0ee6cfa3117aa118bf51994e838639610e4309975d7816`.
The adjacent `registration.json` records `registered-and-verified` at sequence 1
for the exact protected executable above. Its native release ID remains
`celeste-plus-20260927-auth-hardening-01`; the repaired Java catalog is `...-02`.
No native bytes, receipt or registration changed during the Java repair.

Version 129 deployed the complete backend and initial Java catalog. Its source
and artifact checks passed, but the user's subsequent injection exposed an
obfuscated auth return type at the stable `RuntimeInstallationSigner.load`
boundary. The old fixture had missed that call. Profile A now retains auth type
names while protecting their bodies. A regression reproduces the broken
artifact's exact mismatch and passes with the repaired artifact.

| Final repaired deployment | Value |
| --- | --- |
| Fly image | `deployment-01M3HNNW2GBB5P16CJZSJWWMGH` |
| Image digest | `sha256:a6ca0be3c851cd32617a6b8146777319816a66086c472a349d0ca4d402267713` |
| Machine / version | `85d426c4429798` / 130 |
| Health checks | 1/1 healthy |
| Active Plus catalog | `celeste-plus-20260927-auth-hardening-02` |
| Runtime SHA-256 | `763fcdb12517e21b640f853a544a7e40f0c9132552a52274b6da53d57838ffba` |
| Live verification | `build/fly-rollout-20260927/full-fix-live-verification.json` |
| Verification SHA-256 | `307ba96fe5121e59d620fff0b869d25e91f964e668200ba59631e8d16af4be6d` |

The repair's local actual-catalog checks passed 95 tests with one Windows
symlink-permission skip. Live verification matched the complete source and all
23 artifact files, verified fresh native signatures and nonce binding, and
confirmed missing-nonce, forged-publication and unauthenticated resource
requests were refused. Native policy sequence 1 and the Standard release's
artifact bytes and identities were preserved. License data remains on the same
authoritative database. The live probes used no real license; the subsequent
successful game Load is the user's separate confirmation.

The complete backend now requires a fresh Plus catalog nonce. Old Plus clients
must update; the temporary additive deployment's old-protocol compatibility is
not a fallback in the final service. **Fully restart Minecraft before loading
the repair**, because the old Bootstrap A remains resident in its JVM.

## Publication and limits

The GitHub draft for `celeste-plus-20260927-auth-hardening-01` contains the
protected `Celeste+.exe`, `release.json` and `SHA256SUMS.txt`; their final bytes
were verified and the metadata references Java release `...-02`. It is ready
for publication after the source push. Private MAPs, instruction evidence,
publication keys and signed authority envelopes are not public release assets.

The user confirmed “Loads successfully” after restarting and retrying the
repaired deployment. This is user-reported runtime success, not an independently
observed gameplay or performance test. No FPS, steady-state overhead, broad
module coverage or general compatibility claim is made. The executable remains
**Authenticode unsigned**, with Windows **Unknown publisher** explicitly
accepted for this exact release. The normal signing helper remains strict.
