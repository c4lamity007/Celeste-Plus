# Native protection settings for Celeste+

This records the compiled Windows Rust launcher profile and proposed comparison
settings. It is not a protection receipt or a measured performance result. The
Java host and payload have a separate protection pipeline. Keep server
authorization, DPoP, fresh manifests and runtime expiry in place.

## Tool and modes

The local `VMProtect.exe` reports **3.10.4**, file version **3.10.4.2668**. The
vendor lists 3.10.4 as a February 28, 2026 release. The directory identifies this
installation as Lite; verify its license/edition in the tool before saving the
release project. [Vendor release notes](https://www.vmpsoft.com/news)

Lite includes virtualization and mutation. Professional adds the console tool,
scripts and advanced interface; Ultimate adds its licensing and activation
systems. Do not write a Lite workflow that assumes `VMProtect_Con.exe` or Lua
automation is available. [Edition comparison](https://vmpsoft.com/vmprotect/purchase)

The vendor describes Mutation as faster but weaker, Virtualization as the
middle tradeoff, and Ultra as mutation followed by virtualization, with the
highest cost. Use Ultra selectively. No documented setting establishes a
universal optimum for this application. [Compilation modes](https://www.vmpsoft.com/vmprotect/user-manual/working-with-vmprotect/page/2/)

## Profile that compiled on September 27

The successful GUI job selected all seven verified MAP aliases with
**Virtualization**. No selected function used Ultra. The input executable was
unchanged by the alias preparation. The saved project recorded:

| Setting | Observed value |
| --- | --- |
| Saved XML options | `332032`, preserved from the GUI template |
| Memory Protection | On |
| Import Protection | Off |
| Resource Protection | On |
| Pack output | On |
| Strip Debug Information | On |
| Strip Relocations | Off |
| Additional debugger / virtualization-tool detection | Off / off |
| VM complexity | Default 20% |

The installed bundled help describes the symbolic project options but does not
document their serialized numeric bit assignments. Do not derive new masks from
the enum ordering. Change options through the supported GUI and retain its saved
project plus actual compiler evidence.

The emitted output passed the static metadata check and rendered the activation
screen in an initial controlled startup smoke. It failed closed because native
approval could not yet be obtained. Seven transformed-function paths later
passed the exact-artifact verifier and the executable was registered. After the
coordinated Java repair and a full Minecraft restart, the user confirmed “Loads
successfully.” No native performance or broad gameplay result is inferred.
See [the exact run record](protection-run-20260927.md).

## Proposed comparison profile

These choices are engineering recommendations for a separate comparison. They
were not the settings of the completed compile above. In particular, packing
and resource protection were on in that run, and no Ultra comparison was run.

| Setting | Starting choice | Reason / verification needed |
| --- | --- | --- |
| Small authorization decisions | Ultra | Run on activation/load boundaries; keep the selected instruction ranges small. |
| Larger bounded token/catalog validators | Virtualization | Escalate individual regions to Ultra only after comparing latency and size. |
| UI, bulk processing and library internals | Leave native | Avoid spending the budget on frequently executed code or standard algorithms. |
| VM version / instances / complexity | Current/default; default instances; default complexity | Establish a reproducible baseline before changing one variable at a time. Do not call maximum complexity fastest or optimal. |
| Memory Protection | On, subject to compatibility checks | Verify startup, Stop/reload and final signing with this option enabled. |
| Pack output | Off initially | Measure packing separately if distribution size justifies it. |
| Import Protection | Off initially; evaluate with packing | Review the pair rather than changing the whole startup path at once. |
| Resource Protection | Optional, off initially | Rust constants and Java payload content are not automatically covered by a PE resource setting. |
| Strip Debug Information | On | Also retain the repository's independent metadata audit. |
| Strip Relocations | Off | Preserve the current executable's relocation behavior. |
| Lock to Serial Number / Lock to HWID | Off | Preserve Celeste's existing server-side license/device contract. |
| Debugger detection | Off in the baseline | A separate compatibility/product-policy choice; it does not establish authorization. |
| Virtualization Tools detection | Evaluate separately | The existing environment gate remains enabled. Test physical Windows VBS/Hyper-V hosts before adding another detector. |

VMProtect documents default VM instances as 10, increased complexity as adding
file size, and memory integrity checks before the original entry point. It
recommends pairing import protection with packing. Packing with protected
EntryPoint is an optional separate experiment, not a reason to virtualize the
whole launcher. These documented controls do not prove continuous runtime
attestation. [Protection options](https://vmpsoft.com/vmprotect/user-manual)

## Source targets to map to the compiled binary

Frequency below is inferred from call sites, not measured. `native_manifest`
is shared into the Plus launcher through `main.rs`.

| Existing source function | Suggested treatment | Boundary to preserve |
| --- | --- | --- |
| `beta-lab/native-shared/src/native_manifest.rs`: `verify` | Virtualization; consider Ultra after measurement | Pinned signature, nonce, product/platform, lifetime, sequence and executable allowlist must all pass. |
| `beta-lab/native-launcher/src/delivery.rs`: `verify_manifest`, `verify_installer` | Virtualization | Retain the fresh nonce, installer/DPoP binding, exact artifact list and expiry checks. |
| Same file: `identity`, `current` | Ultra candidate | Small product/device/identity and freshness decisions used a bounded number of times per Load. |
| `shared-auth/dpop.rs`: `require_binding` | Ultra candidate | Keep the proof-of-possession comparison; protect its callers' rejection paths too. |
| `beta-lab/native-launcher/src/auth.rs`: `verify_token`, acceptance checks in `authenticate_installer` and `load_credential` | Virtualization around local verification | Whole installer authentication includes network I/O; do not select that entire routine simply because its name contains auth. |
| Same file: `signing_key`, `decrypt_token` | Review only the small validation boundaries | Preserve `Zeroizing`, error exits and existing DPAPI/key-store behavior. Leave Ed25519, X25519, HKDF and AES-GCM implementations native. |
| `beta-lab/native-launcher/src/attach.rs`: `verify_inspection`, `validate_endpoint` | Virtualization candidate | Preserve process identity, game directory and authenticated resident-channel checks; leave status/Stop available for cleanup. |

Leave `App::update`, `App::show_ui`, egui/GL rendering, process/status polling,
attach wait loops, TLS/HTTP transport, `delivery::read_bounded`,
`delivery::sha256`, and `native_manifest::hash_executable` native. Avoid
virtualizing serializers, allocators, filesystem loops and cryptographic library
internals. The bounded acceptance decisions are the useful protection targets;
large hashing loops are the likely cost multipliers.

## Mapping and correctness hazards

The current Cargo profile uses optimization level 3, fat LTO and stripped
symbols. The candidate builder also uses `/DEBUG:NONE` and LLVM stripping.
Source names are therefore **not evidence of selectable or separately emitted
functions**. Inlining can pull bulk hashing or crypto code into a selected
wrapper. Review the final instruction ranges and every relevant call site.

VMProtect supports matching compiler PDB/MAP files, SDK markers, or manual
address selection. Use a private map from the **same linked image**, or a
reviewed marker/address mapping; never reuse old addresses after rebuilding.
Do not ship private symbol files. A new metadata-free build needs its own
mapping and evidence. [Function selection](https://vmpsoft.com/vmprotect/user-manual)

The first GUI compile with raw Rust MAP names did not resolve its selections,
and its output was rejected. `tools/packaging/prepare_vmprotect_map.py` now
derives seven readable aliases from the exact native candidate. It validates the
candidate's executable/MAP hashes, unique target symbols, executable sections,
PE exception-function ranges and simple unwind records before changing only the
MAP labels. Executable bytes and the original MAP remain intact. The derived
MAP and `mapping.json` contain private build details and stay out of Git.

The helper's `validate` command independently re-derives the expected mapping.
Its result is selection evidence only. The GUI must resolve every alias, and an
approved independent verifier must still prove every selected transformation.
Exact prepare/validate commands are in [the runbook](../tools/packaging/README.md).

If SDK markers are introduced later, review Rust calling conventions, optimized
control flow, all `?`/early returns and destruction paths. Never remove
zeroization, move secrets into logs, replace secure randomness, or change
constant-time library code to make a region easier to protect. Virtualization
does not keep live keys secret from the owner of the process.

## Proving the chosen profile

Compare the same source and test scenarios before and after protection: cold
startup, activation/load latency, CPU and working set while idle, rendering
responsiveness, repeated status/Stop/reload, wrong/expired/replayed grants,
offline failures and corrupted artifacts. Record raw timings and a stated
acceptance budget; no FPS or latency improvement is claimed here.

Protect the unsigned candidate, verify the selected ranges and functional
checks, then normally sign and verify the final bytes. The user explicitly
accepted Unknown publisher for the exact September 27 reviewed release; that
documented exception remains Authenticode unsigned. Register the hash of the
actual final file. Keep the project, protector/configuration hashes, compiler/map
identity and independent verification report together. A successful VMProtect
compilation, renamed section or missing strings alone does not establish which
authorization code was virtualized. Follow [the native publication contract](../tools/packaging/NATIVE-RELEASE.md).

`tools/packaging/import_gui_native_candidate.py` can import genuine verified GUI
output into the existing Sign layout. It checks the original and derived MAPs,
alias record, project selection, compiler evidence, source inventory and an
approved protection receipt before creating its completion marker. It does not
create missing verifier evidence or approve a tool. The normal Sign path requires
a fresh verification receipt over the final Authenticode-signed bytes. The
release-specific unsigned exception instead uses the approved exact-artifact
receipt and direct registration. Every path still requires the reviewed
protector/configuration/verifier tuple; no arbitrary unsigned fallback is added.
