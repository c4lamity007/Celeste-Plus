# Celeste+ native publication

This repository builds only `celeste_plus`. It does not build or publish the
separate Standard launcher. `CelestePlus` is the Cargo binary name; Windows
publication uses `Celeste+.exe`.

The protection policy must approve the exact protector/configuration/verifier
tuple used by a release. A compiler build, string obfuscation, or a vendor sample
is not approval to publish a native client.

## Required inputs

- A reviewed licensed protector, Celeste+ configuration, verification tool, and
  adapter. The private ZIP has a pinned SHA-256 and the
  `celeste-native-protection-bundle-1` schema with exactly `celeste_plus` in its
  `products` map. Each tool/configuration hash must be approved in the policy.
- Normally, a reviewed Authenticode identity, its pinned thumbprint, and
  timestamping. The explicit single-release exception below is separate.
- Separate native-publication signing material and its public key configured on
  the server. Production signing secrets are unavailable during protection.
- A protected Java payload/host release verified with the new server protocol.

`build-windows-native-candidate.ps1 -Product celeste_plus` creates an unsigned
candidate, strips reviewed metadata, and records exact source and binary hashes.
`protect-sign-windows-native.ps1` has separate Prepare and Sign phases. Signing
checks the final bytes and requires fresh protection evidence for those bytes.
Both tools require a fresh output directory. Neither uploads or deploys.

`native_protection_gate.py` pins every Rust source tree, Cargo manifest/lock and
embedded icon in this standalone layout. Receipts from the older integrated
repository are not reusable after this source/path change.

`register_native_release.py` validates exact output/protection evidence before
forming a signed publication request. Retain approved existing binary hashes;
only use explicit initial-publication mode after confirming an empty policy.
Use the reviewed API origin `https://qb0f90cf11f4d1d52.fly.dev`.

The CI workflow runs offline checks and archives unsigned platform candidates.
No production release job is enabled in this repository. A source push is not
a service deployment, signed distribution, or in-game verification.

## Explicit Windows publisher exception for September 27

The user authorized release `celeste-plus-20260927-auth-hardening-01` with server
verification and accepted Windows showing **Unknown publisher**. This exception
applies only to the reviewed protected Windows executable with SHA-256
`ad538688e39f7eb1d811379c81a2bd955300984cbd80669f503ccd2623bf89e4`.
Its Authenticode status is **NotSigned**. It has no Windows publisher signature
or timestamp; signed server manifests do not provide those properties.

`verify_reviewed_vmprotect.py` verified seven reviewed function paths reaching
VM operand decoding and dispatch for these exact bytes. The private review is
mechanically bound to the source, original input, saved project, derived MAP,
captured instruction paths and output. The verifier is not a generic detector
or an approval of another output. It does not claim guest-environment tests,
authenticated game attachment, or performance results.

The standard `protect-sign-windows-native.ps1` remains strict and unchanged.
The exception does **not** use or claim its Sign phase. It requires separate
release-specific authorization/evidence, the approved exact protection tuple
and receipt, metadata verification including `NotSigned`, and direct use of
`register_native_release.py`. No automatic unsigned fallback is added.

After those gates pass, an authorized caller supplies the separate publication
key in the helper's child-only environment and registers the reviewed bytes:

```powershell
python tools/packaging/register_native_release.py `
  --binary '<reviewed-Celeste+.exe>' --receipt '<approved-protection-receipt.json>' `
  --release-id celeste-plus-20260927-auth-hardening-01 `
  --product celeste_plus --platform windows-x64 `
  --expected-sha256 ad538688e39f7eb1d811379c81a2bd955300984cbd80669f503ccd2623bf89e4 `
  --sequence '<next-sequence>' --bootstrap-empty `
  --output '<fresh-private-publication-envelope.json>' --publish
```

`--bootstrap-empty` is valid only for the first registration and requires a live
empty-policy check. Later registrations must retain the reviewed existing policy
with `--retain` and a higher sequence. The helper verifies a fresh signed server
response before reporting success. Registration and final publication results
are recorded separately in [the run record](../../docs/protection-run-20260927.md).

This exact executable is now registered and verified at sequence 1. Do not rerun
the initial-publication example against that nonempty policy. The full backend
and repaired Java catalog `celeste-plus-20260927-auth-hardening-02` are live at
version 130; the native executable and native release ID remain unchanged.
After fully restarting Minecraft, the user confirmed successful Load. GitHub
publication is ready but remains draft at this record's freeze.

Publish only the executable and a public hash/summary. Private MAPs, project
addresses, process captures, publication envelopes and private keys stay outside
release assets and Git. Ordinary unsigned CI candidates have no such exception.
