# Celeste+ authentication, obfuscation and release

This guide applies to **Celeste+**, in `c4lamity007/Celeste-Plus`.
The separate Celeste repository supplies the pinned game core only.

The goal is strong protection at authorization boundaries while keeping rendering,
input, cryptography and bulk processing fast. There is no universal “maximum”
setting that achieves both. Use the strongest configuration that passes the
functional and performance checks below, and retain its exact evidence.

## What protects each layer

| Layer | Build/protection tool | Required boundary |
| --- | --- | --- |
| Windows launcher | Rust/MSVC, then VMProtect | Preserve the Plus product, signature verification, DPoP and environment gates. |
| Bootstrap A: resident Java host | Java obfuscator; stable/implementation split | Attach entrypoints, resident bridges and shared API names must stay compatible. |
| Bootstrap B: disposable bootstrap | Java obfuscator | Keep its externally called entrypoints and two-part loader contract. |
| Game modules and runtime auth | Java obfuscator; payload split | Preserve Minecraft/Fabric/mixin/reflection entrypoints and resource names. |
| Authentication server | Live server validation and private signing keys | Neither VMP nor an obfuscated client replaces server authorization. |

VMProtect protects native machine code. It does not turn ordinary Java JARs into
protected native code. Do not enable the Java tool's experimental native modes
without separately verifying their JNI, classloader and platform requirements.

## Authentication flow

1. The native launcher checks the environment and obtains a fresh signed native
   approval manifest from `/v1/client-manifest`. The response binds the nonce,
   `celeste_plus`, platform, validity period and approved executable hash.
2. Enrollment creates an installation signing identity and binds it to the
   account/device. Windows credentials use DPAPI; supported other platforms use
   their account credential stores. Local cache presence never grants access.
3. `/v1/installer/start` and `/v1/installer/complete` establish a fresh signed
   installer grant. The server checks the live license, Plus entitlement, device
   binding and installation proof.
4. `/v1/beta/manifest` requires the grant, an installation-signed DPoP proof and a
   fresh request nonce. The response binds the exact grant, device, product and
   catalog. Every artifact request is independently authorized on the server.
5. The native launcher verifies and loads Bootstrap A. A revalidates before
   loading B and before invoking the two-part payload; mismatches fail closed.
6. Runtime activation requires a fresh server check. Heartbeats renew the signed
   lease; a separate monotonic deadline stops authorization after its 90-second
   lifetime even if a heartbeat worker stalls. Revocation and the server kill
   switch affect subsequent checks; an already issued lease has its own expiry.

Code paths and test evidence are in [the authentication record](../../docs/auth-hardening-20260927.md).
The service source serves both products, while this release and its clients are
scoped to `celeste_plus`.

## Protection profile

### Java

Use the checked-in profiles under `beta-lab/obfuscation/profiles/` and the
reproducible Java release runner. Their compatibility boundaries are intentional:

- Rename A/B implementation classes and private members only within their
  reviewed boundaries. Preserve externally invoked lifecycle and attach names.
- Keep game payload class/member names stable for current mixin/reflection
  compatibility. Protect its strings and remove debug information.
- Use composite string protection with the reviewed double pass. Keep expensive
  string modes, triple/quad passes, arbitrary flow changes, asset relocation and
  whole-program native conversion off until separately measured and verified.
- Keep bridge/library bytes stable and regenerate descriptors/catalog hashes
  **after** obfuscation. Never mix A/B/payload artifacts from different builds.

These are tested compatibility choices, not a claim that strings alone make a
client unpatchable. Any stronger profile must rerun the same artifact, lifecycle,
reflection and performance gates.

### Native VMProtect

Use the installed, registered **VMProtect Lite 3.10.4** GUI. Lite has no console
compiler; do not pass console arguments to `VMProtect.exe` and describe that as
CI automation. [Official edition comparison](https://vmpsoft.com/vmprotect/purchase)

| Control | Initial profile |
| --- | --- |
| Small identity/freshness/DPoP decisions | Ultra, only for individually mapped functions |
| Bounded signature/catalog/token validators | Virtualization; compare Ultra separately |
| UI/rendering, polling, bulk hashing, crypto/TLS libraries | Native |
| Memory protection | On, then verify compatibility |
| VM version / instances / complexity | Current/default / default / default (20% in the installed GUI) |
| Pack output / import protection / resource protection | Off for the measured baseline |
| Strip debug | On |
| Strip relocations | Off |
| VMP serial/HWID locking | Off; Celeste's server/device policy remains authoritative |
| Additional VMP debugger/VM detectors | Separate compatibility experiment; existing Celeste VM/sandbox gate stays enabled |

The vendor describes Ultra as the expensive mutation-plus-virtualization mode.
Applying it to everything is not a performance optimization.
[Official mode documentation](https://www.vmpsoft.com/vmprotect/user-manual/working-with-vmprotect/page/2/)

See [exact source targets and mapping hazards](../../docs/protection-settings.md).
Fat LTO can inline a named function or pull other code into it. Inspect the actual
linked image instead of assuming each source function remains a separate target.

## Run the build

Run from the Celeste-Plus repository with Java 21, Python 3.11+, Rust/MSVC,
PowerShell 7 and `llvm-tools-preview` installed. Keep generated artifacts,
obfuscation mappings, VMP projects with build addresses and signing material
outside Git.

### 1. Verify the source and game input

```powershell
git remote get-url --push origin
.\gradlew.bat --no-daemon check assemble
python -m unittest discover -s tests -p test_prepare_runtime.py
python tools/prepare_runtime.py --core-checkout '<accessible-Celeste-checkout>'
```

The remote must be `https://github.com/c4lamity007/Celeste-Plus.git`.
Preparation verifies `core.lock.json`, base hashes and the runtime overlay. It
requires a fresh output directory and never deletes an existing build. Use its
documented `--build` and explicit media-DLL option when preparing a new runtime.
Build the remapped runtime JAR; do not package raw development/named classes.

### 2. Protect and validate Java artifacts

Use [`tools/protect_java_release.py`](../protect_java_release.py). Inspect the
producer before running it; the pin covers its entry JAR **and every manifest
dependency**, not just the main JAR. Review that identity before accepting it.

```powershell
python tools/protect_java_release.py inspect-tool --obfuscator '<approved-tool.jar>'
python tools/protect_java_release.py run --help
```

An invocation with explicit inputs is:

```powershell
python tools/protect_java_release.py run `
  --input '<remapped-runtime.jar>' `
  --obfuscator '<approved-tool.jar>' `
  --tool-sha256 '<reviewed-bundle-sha256>' `
  --parent-classpath '<parent-classpath.txt>' `
  --java-home '<Java-21-JDK-directory>' `
  --descriptor-module 'build/runtime-core/tools/package_final_release.py' `
  --release-id 'celeste-plus-protected-candidate' --client-version '<version>' `
  --base-url 'https://qb0f90cf11f4d1d52.fly.dev' `
  --server-key '<existing-public-service-verification-key>' `
  --output 'build/protection/new-java-candidate'
```

Replace the quoted placeholders. The parent-classpath file contains one absolute
game/library JAR path per line, matching the intended Minecraft runtime. The
server key is the existing **public verification key**, never a private key.
`--offline` uses available Gradle dependencies; it does not skip verification.
The runner records its input, producer, profiles, source and output hashes in
`receipt.json` and keeps every stage log beside it. Its order is:

1. Snapshot the approved Java obfuscator and its dependency JARs with hashes.
2. Split the full remapped runtime into stable and transformable parts.
3. Protect the transformable payload; merge unchanged boundaries back in.
4. Prepare the final two-part payload, descriptor, host support and catalog.
5. Build matched A/B artifacts using that prepared package and layout.
6. Protect A/B implementations and merge their stable boundaries.
7. Verify protected artifacts and assemble the matching release directory.

The actual Java tool command is `java -jar <approved-tool.jar> --config <profile>`.
Starting it without arguments opens its GUI. Paths and profiles must refer to the
same fresh run. Do not rerun a stage over existing output or borrow an old
protected bootstrap merely because its filename matches.

### 3. Build the native input with a matching private map

```powershell
pwsh -NoProfile -File tools/packaging/build-windows-native-candidate.ps1 `
  -Product celeste_plus -OutputDirectory '<fresh-native-directory>' -MapForProtection
```

This creates `Celeste+.exe`, its matching `Celeste+.map`, candidate source hashes
and metadata results. MAP flags apply only to the final Rust link. Keep the map
private and never use addresses from another build.

### 4. Run VMProtect Lite

1. Open the fresh `Celeste+.exe` with its same-build `.map` beside it.
2. Add only the mapped authorization functions listed in the settings document.
   Confirm their addresses and inspect any code absorbed through inlining.
3. Choose each function's mode, review the options above, and save the `.vmp`
   project beside this input. Preserve this GUI-generated template; do not invent
   undocumented numeric option masks.
4. Set a distinct output such as `Celeste+.vmp.exe`. Keep the unsigned input.
5. Compile once and read the complete result. Decline the automatic Execute
   prompt; run controlled verification separately.
6. Record protector, project, input, MAP and output hashes, selected functions,
   compilation messages and test results. Compilation alone is not proof of
   protected coverage or runtime correctness.

If using the installed `vmprotect-control` MCP bridge, its supported order is
`vmp_status`, `vmp_inspect_project`, `vmp_prepare_project`, `vmp_open_project`,
`vmp_gui_state`, `vmp_compile`, then `vmp_job_result`. The standalone Plus input
directory must be within its explicitly configured allowed roots. A project with
no selected procedures is not a protection preset. The bridge needs an unlocked
desktop and does not supply signing or an independent protection verifier.

### 5. Verify function, performance and final bytes

Compare unprotected and protected artifacts from the same source. Run at least
five cold samples where practical and record median/p95 rather than one timing.
Measure startup/load, idle CPU/working set and repeated Stop/reload. Verify valid,
wrong, expired, replayed and corrupted inputs. Game verification must use the
actual build and include rendering responsiveness.

Treat no functional regressions as mandatory. Set an explicit latency/size budget
for the intended hardware before escalating individual functions to Ultra or
raising complexity. No unmeasured FPS or “zero overhead” claim is acceptable.

Then protect the unsigned native input, sign it with the reviewed Authenticode
certificate, verify the signature/timestamp, and run the independent protection
verifier against those final signed bytes. The publication receipt must bind the
current source, approved protector/config/verifier and final executable hash.
`protect-sign-windows-native.ps1` enforces separate Prepare/Sign phases; its
approved adapter bundle must support the installed tool edition. Its Prepare
phase builds its own candidate, and Sign requires that phase's
`protected-candidates.json`. It cannot currently adopt the manually protected
candidate from steps 3–4. Integrating Lite requires a reviewed adapter workflow
that retains the matching MAP and writes genuine verification evidence; manual
GUI compilation does not supply that adapter or approve its output.

## Publish in the correct order

1. Pass offline and real-runtime verification for the complete matched release.
2. Configure the server's separate native-publication **public** key; keep its
   signing private key in the release secret store, never the client or repo.
3. Stage the compatible backend and all required payload artifacts together.
   The new mandatory nonce protocol makes a server-only rollout unsafe for old
   clients; retain or explicitly migrate the supported client population.
4. Register the exact **final signed executable** through
   `register_native_release.py`, retaining existing approved hashes where needed.
5. Confirm the fresh native manifest, Plus entitlement, payload delivery,
   activation/heartbeat, revocation and Stop with the new loader.
6. Publish the matched native/payload release, then verify download hashes.
7. Commit/push source only to `Celeste-Plus`. Unsigned CI artifacts are test
   candidates, never the production release.

A new loader is required for the new native approval/nonce protocol. Rebuilding
or VMP-protecting it changes its hash; signing changes it again. Register only
the final bytes that users receive.

## Current prerequisites

The checked-in protector policy is intentionally empty until actual Celeste
coverage and verification are established. At this run's readiness check, no
code-signing certificate or GitHub release secrets were configured, the live
native approval endpoint returned 404, and the publication public-key setting
was absent. See the run evidence for what was actually built and verified.

The [September 27 run record](../../docs/protection-run-20260927.md) separates
completed Java/native builds from the remaining native protection and production
release prerequisites.

These are missing release inputs, not confirmation prompts. Do not fabricate a
receipt, approve a tool based only on its filename, substitute a self-signed
production certificate, or deploy an incompatible backend to finish the checklist.
The [native publication contract](NATIVE-RELEASE.md) stays enforced.
