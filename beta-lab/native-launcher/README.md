# Celeste+ native launcher

The standalone crate is `celeste-plus-launcher`; its executable is `CelestePlus`
(`CelestePlus.exe` on Windows). Source is imported from integrated Celeste commit
`ae01f2d08bb70904cc8ff95102e56f555662e870`, with only repository-relative module
paths, the local icon path, and the crate/executable names adapted here. Rust
source is formatted with `cargo fmt` for the standalone CI gate.

## Build and tests

From this repository root, with the platform's Rust/C toolchain installed:

```powershell
cargo test --locked --offline --manifest-path beta-lab/native-launcher/Cargo.toml
cargo build --locked --offline --release --manifest-path beta-lab/native-launcher/Cargo.toml
```

Offline commands require dependencies already present in Cargo's cache. Omit
`--offline` for the initial dependency download. Rust must support edition 2024
(1.85 or newer); locked dependencies may require a newer compiler. Windows is
verified locally; Linux/macOS candidates retain their platform-specific code.
Release output is `beta-lab/native-launcher/target/release/CelestePlus.exe` on
Windows, or `CelestePlus` on Unix.

All compile-time source/assets are inside this repository:

- `src/`: Plus UI, enrollment/authentication, signed delivery, explicit process
  selection, attachment/control, and account-protected storage.
- `../native-shared/src/`: environment checks, signed native executable allowlist,
  portable hardware/credential handling, and account data-directory resolution.
- `../../shared-auth/`: shared request proofs and secret-buffer regression tests.
- `assets/celeste-logo.png`: embedded launcher icon.
- `../native-shared/tests/native-manifest-golden.json` and
  `../delivery-client/fixtures/server-schema2-golden.json`: public deterministic
  signed protocol fixtures used by offline tests.

Neither the Standard launcher nor an adjacent integrated checkout is required.
Shared protocol/channel values and the `celeste_plus` entitlement remain intact;
this repository layout change does not create a new device identity or cache.

## Authentication and delivery

The license screen comes first. Saved activations are revalidated with the server,
and each Load performs fresh authorization. Credentials remain protected by the
current account's platform key store; there is no plaintext fallback.

Detected guest VMs and sandboxes are rejected before enrollment, authentication,
and attachment. Required unavailable host facts fail closed. A hypervisor flag
alone does not classify a physical Windows VBS/Hyper-V host as a guest.

The launcher checks its current executable against a fresh, nonce-bound,
server-signed native manifest before authentication. The beta artifact catalog is
also nonce-bound and signature checked. Downloads are restricted to signed
artifacts; the selected PID, process start time, game directory, release and
catalog remain bound through the attachment lifecycle.

A locally compiled executable is a development candidate, not a registered
release. It cannot pass the live executable allowlist until its final protected
and signed bytes are published through the approved registration process.
The build commands above do not protect, sign, register, deploy, or publish it.

## Verification boundary

The ordinary test suite uses public fixtures and owned temporary files, including
nonsecret current-user Windows DPAPI/known-folder checks and a local Java process
identity probe when available. It does not read saved real credentials or contact
the live auth service. Explicitly ignored probes require selected Java helper
fixtures or real host evidence and are not included in offline verification. Compile/tests do not
prove live enrollment, delivery, attachment, game behavior, UI appearance, or
stop/reload cleanup. Those need a compatible authorized local game and a
registered protected native build.
