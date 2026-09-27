# Celeste+ native publication

This repository builds only `celeste_plus`. It does not build or publish the
separate Standard launcher. `CelestePlus` is the Cargo binary name; Windows
publication uses `Celeste+.exe`.

The checked-in protection policy has an empty approval list. A compiler build,
string obfuscation, or a vendor sample is not approval to publish a native client.

## Required inputs

- A reviewed licensed protector, Celeste+ configuration, verification tool, and
  adapter. The private ZIP has a pinned SHA-256 and the
  `celeste-native-protection-bundle-1` schema with exactly `celeste_plus` in its
  `products` map. Each tool/configuration hash must be approved in the policy.
- A reviewed Authenticode identity, its pinned thumbprint, and timestamping.
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
