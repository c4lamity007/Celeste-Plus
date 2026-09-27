# Celeste+ authentication, obfuscation and release

This guide applies to **Celeste+**, in `c4lamity007/Celeste-Plus`.
The separate Celeste repository supplies the pinned game core only.

For the next client/server protocol, manual-sales policy, verification results,
certificate rotation, release prerequisites and rollback steps, read the
[protocol 2 record](../../docs/auth-protocol-v2-20260927.md). The historical
seven-target native approval below applies only to its exact old bytes.

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

### Native VMProtect: historical seven-function approval

Use the installed, registered **VMProtect Lite 3.10.4** GUI. Lite has no console
compiler; do not pass console arguments to `VMProtect.exe` and describe that as
CI automation. [Official edition comparison](https://vmpsoft.com/vmprotect/purchase)

The historical September 27 successful compile used the following saved GUI settings.
The exact-artifact verifier has since verified all seven reviewed transformation
paths. Performance and authenticated attachment remain unverified.
[Run evidence](../../docs/protection-run-20260927.md)

| Control | Compiled candidate |
| --- | --- |
| Seven validated authorization aliases | Virtualization for all seven; no Ultra |
| UI/rendering, polling, bulk hashing, crypto/TLS libraries | Native |
| Memory protection | On, then verify compatibility |
| VM version / instances / complexity | Current/default / default / default (20% in the installed GUI) |
| Pack output / import protection / resource protection | On / off / on |
| Strip debug | On |
| Strip relocations | Off |
| VMP serial/HWID locking | Off; Celeste's server/device policy remains authoritative |
| Additional VMP debugger/VM detectors | Off; existing Celeste VM/sandbox gate stays enabled |

The saved template records `Options="332032"`. Preserve the GUI-generated
template; the bundled help does not document its serialized bit assignments.
Packing/resource protection off and selective Ultra remain separate comparison
runs, with no performance result claimed for either configuration.

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

### 4. Prepare aliases and run VMProtect Lite

The raw Rust names did not resolve in the first GUI compile. Create a fresh
private alias bundle using the matching input, original MAP and candidate record:

```powershell
python tools/packaging/prepare_vmprotect_map.py prepare `
  --binary '<native-input>/Celeste+.exe' `
  --original-map '<native-input>/Celeste+.map' `
  --candidate '<native-input>/candidate.json' `
  --output-directory '<fresh-alias-directory>' --profile auth-v2-14

python tools/packaging/prepare_vmprotect_map.py validate `
  --binary '<alias-directory>/Celeste+.exe' `
  --original-map '<alias-directory>/Celeste+.original.map' `
  --candidate '<alias-directory>/candidate.json' `
  --alias-map '<alias-directory>/Celeste+.map' `
  --mapping-record '<alias-directory>/mapping.json' --profile auth-v2-14
```

Preparation copies the executable byte-for-byte, retains the original MAP and
changes only the selected profile's reviewed MAP labels. It verifies candidate hashes, unique
symbols, executable ranges and PE exception/unwind boundaries. `mapping.json`
is private selection evidence, with status `mapping-only-not-protection-evidence`.
Keep the entire bundle private. Rebuilds require a new matching bundle.

`historical-7` remains the default and preserves the old mapping format. The
explicit `auth-v2-14` profile adds signed TLS policy verification, the TLS leaf
verifier, artifact decryption, debugger rejection, HWID v2 retrieval, prelaunch
validation and grant requests. It requires all 14 exact linked function starts;
neither the MAP validator nor the importer infers a profile from an input record.
This is target selection, not approval of a new protected binary.

1. Open the alias bundle's unchanged `Celeste+.exe` with its derived `.map`
   beside it.
2. Add exactly the selected profile's validated aliases. Confirm that every selection resolves
   and inspect any code absorbed through inlining. Use Virtualization to reproduce
   the successful compile; evaluate Ultra in a separate comparison.
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
`vmp_gui_state`, then one `vmp_compile`. Poll `vmp_compile_status` with bounded,
read-only calls until completion or failure, then read `vmp_job_result`. Do not
submit another compile to obtain status or accept the automatic Execute prompt.
The standalone Plus input
directory must be within its explicitly configured allowed roots. A project with
no selected procedures is not a protection preset. The bridge needs an unlocked
desktop and does not supply signing or an independent protection verifier.
The exact input root for the recorded September 27 run was approved and configured.

The repository wrapper prepares a fresh alias bundle, narrows allowed roots for
that invocation, and preserves compiler evidence. It has a bounded timeout and
does not issue a protection receipt:

```powershell
pwsh -NoProfile -File tools/packaging/run_vmprotect_mcp.ps1 `
  -InputDirectory 'build/protection-20260927-v2/native-candidate-02' `
  -Profile auth-v2-14 `
  -ProjectTemplate '<reviewed-v2-template.vmp>' `
  -McpPluginDirectory '<installed-vmprotect-control-plugin>' `
  -McpPython '<MCP-python.exe>' `
  -ReviewedConfig '<reviewed-vmp-config.json>' `
  -OutputDirectory '<fresh-private-MCP-output>'
```

The current local v2 selection uses these exact inputs; none is a protected
publication artifact:

| Local evidence | SHA-256 |
| --- | --- |
| `build/protection-20260927-v2/native-candidate-02/Celeste+.exe` (7,601,664 bytes) | `9298b5c89f8966acd346af07c3bad70b6adb81f1240de1e9d7842afca7dd2097` |
| Matching original private MAP | `76af2433913d9373645e38c97b6b5e98b008b0d40f659a7013c04ac200a29b66` |
| `build/protection-20260927-v2/aliased-input-02/mapping.json` | `77330905c5a9c4214c22a81cb549cf4e5f36401ae481aea4195ca5c7e16c0bbe` |
| `build/protection-20260927-v2/auth-v2-14.template.vmp` | `bc840eea370c09f27ce8203f48cd43233bb8d2f19cabc4115b7518efba336d82` |

The template retains the historical GUI-exported `Options="332032"` and 20%
complexity; only input/output paths and the 14 alias selections were replaced.
All selections request Virtualization. It is a derived template, not a fresh
GUI export or independent coverage evidence. The release build passed locked
Rust 1.97.1 compilation with warnings denied and metadata stripping. Private
MAPs, addresses and projects stay out of Git and public downloads.

### 5. Verify function, performance and final bytes

Compare unprotected and protected artifacts from the same source. Run at least
five cold samples where practical and record median/p95 rather than one timing.
Measure startup/load, idle CPU/working set and repeated Stop/reload. Verify valid,
wrong, expired, replayed and corrupted inputs. Game verification must use the
actual build and include rendering responsiveness.

Treat no functional regressions as mandatory. Set an explicit latency/size budget
for the intended hardware before escalating individual functions to Ultra or
raising complexity. No unmeasured FPS or “zero overhead” claim is acceptable.

Obtain genuine independent transformed-code evidence for the unsigned GUI output.
The verifier must already be approved by `native-protection-policy.json`. Its
receipt/report must bind the input, source, protector, saved project, output,
original MAP, derived MAP, alias record and actual compiler evidence, including
the transformation and mode of every selected function. A completed GUI job or
changed output bytes cannot substitute for that report.

An entry thunk or high section entropy alone does not prove virtualization.
Review the actual loaded/depacked paths from each selected function into VM
operand decoding and dispatch, tied to the exact input/output/project and
compiler evidence. The historical exact-artifact verifier remains pinned to its
old seven-function artifact; it cannot approve a new 14-function build. Runtime
authentication, game attachment and performance remain separate checks.

The GUI importer can then produce the normal Sign layout:

```powershell
python tools/packaging/import_gui_native_candidate.py `
  --candidate-directory '<original-native-input-directory>' `
  --protected-binary '<actual-GUI-output.exe>' `
  --receipt '<genuine-approved-unsigned-protection-receipt.json>' `
  --compile-evidence '<actual-compiler-evidence-file>' `
  --derived-map '<alias-directory>/Celeste+.map' `
  --map-alias-record '<alias-directory>/mapping.json' `
  --profile auth-v2-14 `
  --release-id '<release-id>' `
  --output '<fresh-sign-layout-directory>'
```

The importer does not execute a verifier, approve a tool, sign, or publish. It
revalidates all inputs, checks metadata and current source hashes, and writes
`protected-candidates.json` only after successful validation. The protection
policy refuses any unapproved protector/configuration/verifier tuple.
The explicit v2 profile additionally requires its validated alias evidence and
coverage of all 14 functions. It does not expand the approved protection policy.

For the normal Authenticode-signed path, after a genuine import succeeds, sign
with the reviewed Windows publisher identity:

```powershell
pwsh -NoProfile -File tools/packaging/protect-sign-windows-native.ps1 `
  -Mode Sign -Bundle '<approved-adapter-bundle.zip>' `
  -BundleSha256 '<reviewed-bundle-sha256>' -ReleaseId '<same-release-id>' `
  -OutputDirectory '<sign-layout-directory>'
```

Supply signing credentials through the existing protected release environment,
never command text or source. The Sign phase verifies its approved adapter bundle,
signs and verifies the signature/timestamp, then runs the independent verifier
again against the final signed bytes. The final publication receipt binds those
exact bytes. The separate Prepare phase remains available for approved automated
adapters; importing GUI output does not bypass the Sign or publication gates.

For the reviewed September 27 Windows release, the user explicitly accepted
**Unknown publisher** and authorized an Authenticode-unsigned executable with
server verification. That release-specific exception uses the exact reviewed
protected bytes and still requires the approved protection policy/receipt and
authenticated native registration. It does not make the Sign command above
accept a missing certificate, add an automatic unsigned fallback to CI, or
replace Windows publisher verification with an equivalent certificate.

This exception uses the approved exact protection receipt and independent
metadata check, then the existing direct registration helper. No Prepare/Sign
Sign step is claimed. [Exact scope and registration usage](NATIVE-RELEASE.md#explicit-windows-publisher-exception-for-september-27)

The executable's final SHA-256 is the identity registered with the service.
Publication records must state `Authenticode unsigned`; signed publication
envelopes and nonce-bound server manifests are separate checks. Do not describe
the binary as signed or imply the Windows warning has been removed.

## Publish in the correct order

1. Pass offline and real-runtime verification for the complete matched release.
2. Configure the server's separate native-publication **public** key; keep its
   signing private key in the release secret store, never the client or repo.
3. Stage the compatible backend and all required payload artifacts together.
   The new mandatory nonce protocol makes a server-only rollout unsafe for old
   clients; retain or explicitly migrate the supported client population.
4. Register the exact **final executable** through
   `register_native_release.py`, retaining existing approved hashes where needed.
   Normally these are Authenticode-signed bytes; the explicitly approved
   September 27 exception retains the reviewed unsigned bytes.
5. Confirm the fresh native manifest, Plus entitlement, payload delivery,
   activation/heartbeat, revocation and Stop with the new loader.
6. Publish the matched native/payload release, then verify download hashes.
7. Commit/push source only to `Celeste-Plus`. Unsigned CI artifacts are test
   candidates, never the production release.

A new loader is required for the new native approval/nonce protocol. Rebuilding
or VMP-protecting it changes its hash; signing changes it again. Register only
the final bytes that users receive.

### Separate native publication identity

The publication authority signs native allowlist updates. It is separate from
both the existing service response-signing key and the Windows Authenticode
certificate. `native_publication_identity.py` stores its Ed25519 seed using
Windows current-user DPAPI, outside Git, with no plaintext fallback:

```powershell
# For an explicitly new identity only; the parent directory must already exist.
python tools/packaging/native_publication_identity.py init `
  --identity '<absolute-private-directory>/native-publication.identity.json'

# For the existing identity: verify DPAPI access and print public metadata only.
python tools/packaging/native_publication_identity.py show-public `
  --identity '<absolute-private-directory>/native-publication.identity.json'
```

`init` refuses an existing file and does not rotate a key implicitly. Neither
command changes Fly, signs a Windows binary or publishes a release. Install only
the returned public key as `CELESTE_NATIVE_RELEASE_PUBLIC_KEY` on the server;
this public setting does not need secret storage. Retain the encrypted identity
outside the checkout under the Windows account that created it.

An authorized registration caller can load the seed with `load_private_seed`
and supply its canonical base64url value as `CELESTE_NATIVE_RELEASE_PRIVATE_KEY`
in a fresh child-only environment for `register_native_release.py`. Never print
the value, put it in command arguments/plaintext files, or persist it in the
parent environment. There is no export-private command. DPAPI protects storage;
it does not isolate the key from code already running as that Windows user.

The September 27 identity is already created and its public key installed. Use
`show-public` for inspection; do not initialize another key to rerun publication.
Its fingerprint and deployment evidence are recorded in the
[native infrastructure rollout](../../docs/native-infrastructure-rollout-20260927.md).

## Historical September 27 native release

The full backend and repaired Java catalog `celeste-plus-20260927-auth-hardening-02`
were deployed and verified at Fly version 130. Native release `...-01` was registered
at sequence 1 with the approved exact protection receipt and seven reviewed
virtualized functions. The former endpoint 404, missing publication key and
empty policy findings are historical. Standard artifact bytes and identities
were preserved; the final Plus protocol requires the new client's catalog nonce.

The initial Java release passed older fixtures but failed the actual host-to-auth
call during injection. Profile A now keeps auth class names while protecting
their bodies, and the new regression checks the real call descriptors. The
repaired A/B artifacts pass 442 assertions per run. **Fully restart Minecraft**
to unload the old resident A before retrying. The user then confirmed “Loads
successfully”; no game performance or general module test is implied.

The [GitHub release](https://github.com/c4lamity007/Celeste-Plus/releases/tag/celeste-plus-20260927-auth-hardening-01)
is published; the executable and both metadata downloads passed hash verification. The user
authorized **Unknown publisher** for this exact Windows release. The executable
is **Authenticode unsigned**; the strict Sign helper was not used or weakened.
The local publication identity supplies neither a Windows certificate nor
GitHub release secrets. See the [September 27 run record](../../docs/protection-run-20260927.md)
for receipt, deployment and artifact hashes. The [native publication contract](NATIVE-RELEASE.md)
remains enforced for future releases; no unsigned fallback or blanket protection
approval has been added.
