# Celeste+ macOS and Linux build boundary

Current development is in `c4lamity007/Celeste-Plus`. The September 20 branch
and CI links below are historical evidence from the integrated workspace.
The standalone workflow now builds the host independently of Minecraft and
archives `CelestePlus` candidates. Runtime modules are reconstructed through
`core.lock.json` and `runtime-overlay/`. Fresh native hash approval is required
for the new auth protocol; historical channel identifiers do not replace it.

## Mac consent revision — 2026-09-20

The protected Mac release branch is
`codex/celeste-plus-portable-release-20260920` in the private Celeste repository.
Its native workflow now builds Apple Silicon and Intel separately and runs the
Mac packaging/OS checks against that same compiled revision. The setup build
label is `20260920.4-macos`; Windows and Linux identifiers are unchanged.

The [final native/OS run](https://github.com/c4lamity007/Celeste/actions/runs/35563900738)
passed on both Mac architectures: 59 native tests each, the explicitly authorized
synthetic Keychain fixture, and direct application startup. The verified preview
ZIPs are in `Downloads/Celeste Launchers - Updated/`. Both are ad-hoc signed;
live Minecraft/authentication and first-use user consent remain unverified.

The first native Mac builds passed, but the real Keychain integration check
found that the native creator could read its credential while the separate
Java reader required macOS consent. A successful compile did not catch this.
The corrected Plus launcher checks its fixed `/usr/bin/security` reader before
enrollment, allows up to 120 seconds for consent, then checks a fresh reader with
a five-second deadline. Cached Plus activations are checked before they become
ready too. Denial, timeout, invalid output, or a different key fails closed.
No new license slot is consumed after a failed enrollment preflight.

The user must approve access to the Celeste Keychain item; choosing **Always
Allow** permits later runtime reads without repeated prompts. The launcher does
not change an item's ACL, trust every application, reset a key, use a plaintext
fallback, or weaken the server protocol. Two successful reads are a readiness
check, not proof that the user permanently approved access. A subsequent OS
denial still fails closed. Standard Celeste's cached-activation path is outside
this Plus-only revision.

The Mac CI fixture uses two disposable Keychains on a GitHub-hosted Mac. It
checks that a default native-created item is not silently cross-readable, then
tests the production native preflight and Java reader using a synthetic item
explicitly authorized for those readers. It also rejects a damaged envelope.
That positive fixture is **not an automated first-use consent-dialog test**.
The original default Keychain and search list are verified after restoration.

Mac packages are ad-hoc signed `.app` previews with icons, executable hashes,
source provenance, and startup evidence. No Developer ID/notarization identity
is configured. A direct executable startup check does not establish Finder or
Gatekeeper approval, and no live Minecraft/authentication pass is implied.
Use normal per-app macOS approval; never disable Gatekeeper globally. See the
release evidence under `build/module-qa/plus-platform-release-20260920/ci/` for
actual run results rather than assuming the packaging gate has passed.

## General portability checks

Celeste+ has isolated native CI gates for Windows x64, Linux x64, macOS arm64,
and macOS x64 in `.github/workflows/celeste-plus-platform.yml`. Each runner
uses its native Rust host target, compiles Java with Temurin 21, and:

- format-checks, tests, and release-builds `beta-lab/native-launcher`;
- compiles the Minecraft payload and runs bootstrap A, bootstrap B, and
  combined-hook fixtures;
- verifies that the executable has the expected PE, ELF, or Mach-O architecture;
- records dynamic-library/ABI evidence, including the highest referenced GLIBC
  symbol for the Linux candidate;
- archives Linux binaries with mode `0755`, packages macOS candidates as a
  conventional `Celeste+.app` bundle, and includes a SHA-256 sidecar plus a
  nonsecret build manifest; and
- uploads a seven-day **unsigned test candidate** for that platform.

The Linux candidate is built natively on Ubuntu 22.04 rather than cross-compiled,
and CI rejects a binary that references a GLIBC symbol newer than 2.35.
Its exact GLIBC symbol floor and dynamic dependencies are included as
`native-abi.txt`; consumers should use that evidence instead of assuming all
Linux distributions are compatible.

A developer-side Ubuntu 24.04 WSL run passes the native test suite, but
its locally linked binary references GLIBC 2.39. That local binary is not the CI
candidate and must not be redistributed as one. The separate Ubuntu 22.04 CI
job rebuilds from source and enforces the GLIBC 2.35 ceiling described above.

The workflow never contacts Celeste authentication, Fly, or Minecraft services.
It does not use a license, enroll a device, change an instance, sign/notarize an
application, or publish a release. Native identity fixtures launch an owned
synthetic Java process, and IPC fixtures attach only to their own synthetic test
JVM; they never attach to Minecraft or another user process. Dependency
resolution is limited to the checked-in Gradle/Cargo declarations and lockfile
plus the GitHub-hosted checkout, Java setup, and artifact actions. It does not
execute a downloaded installer or pipe downloaded content into a shell.

## Runtime prerequisites

- A full Java 21 JDK is required; a JRE alone does not provide `jdk.attach`.
- Run Minecraft and Celeste+ as the same desktop account. A sandboxed launcher
  with a different PID/filesystem namespace is not validated by this port.
- Linux needs a graphical X11 or Wayland session, D-Bus, and an unlocked Secret
  Service collection. A headless CI compile does not establish those runtime
  conditions. Process inspection also depends on the user's `/proc` visibility.
- macOS previews are ad-hoc signed, not Developer ID signed or notarized. A
  Developer ID/notarization setup is still needed for an Apple-trusted release.
  Gatekeeper, first-use consent, and JDK attach/gameplay behavior need real-user
  validation on both Apple Silicon and Intel. The native build targets macOS
  11.0; the hosted native checks run on macOS 15, not every supported OS version.
- The existing Spotify/system-media DLL integration remains Windows-only and
  returns no media on macOS/Linux. It does not block the rest of the client.

## What still needs real-machine testing

Compile/test-only CI proves source portability, pure offline behavior, Java
linkage fixtures, output architecture, and packaging. It does **not** prove the
native window, Keychain/Secret Service prompts, Minecraft process discovery, launcher
profile discovery, live authentication, payload download, Attach API behavior,
gameplay, Stop/unload cleanup, or cross-distribution Linux compatibility.
The separate Mac OS gate adds direct window startup and explicitly authorized
synthetic Keychain interoperability, not first-use consent or live gameplay.

The earlier portability review exercised the fixture sets on genuine Windows
and in Ubuntu 24.04 WSL: bootstrap A (320), owned-JVM IPC (34), resident support
(20), offline signer (18), platform-specific CSP2 protection, bootstrap B (101), and
memory-runtime (16) checks. These are useful regression gates, not macOS gameplay
evidence. The later Mac Keychain work is described above. Linux Secret Service
desktop integration and live Minecraft attach/gameplay remain unverified.

The unsigned candidate is also not runtime-validated merely because it builds.
The native protocol deliberately uses the stable `LAUNCHER_CHANNEL_ID`,
`LAUNCHER_CHANNEL_DIGEST`, and protocol version in `release.rs`; the reported
channel digest is **not** the executable's own hash. A rebuild preserving these
values does not require adding its binary SHA-256 to the server installer
allowlist. Do not widen that allowlist for platform binaries. A real rollout
still requires an explicitly approved portable bootstrap/release catalog entry
and verification of the protected artifacts. This candidate workflow performs
neither deployment nor changes to server authentication.

Before calling a platform fully runtime-validated, test it on a clean machine
using an authorized license and a disposable local Minecraft 1.21.11 Fabric
instance. Developer ID signed/notarized Mac builds need their own recheck; Linux
testing should cover at least one X11 and one Wayland desktop. Neither a preview
publication nor the offline workflow substitutes for those checks.

## Implementation references

Process selection reads executable identity, owner, and creation time, never
Minecraft arguments or account tokens. Native creation times match the Java 21
epoch-millisecond contract; fixtures compare them against a live owned Java child.
See [Java Attach API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.attach/com/sun/tools/attach/VirtualMachine.html),
[OpenJDK Linux process timing](https://github.com/openjdk/jdk21u/blob/master/src/java.base/linux/native/libjava/ProcessHandleImpl_linux.c),
and [OpenJDK macOS process timing](https://github.com/openjdk/jdk21u/blob/master/src/java.base/macosx/native/libjava/ProcessHandleImpl_macosx.c).
Default profile paths follow [Modrinth's documented storage locations](https://support.modrinth.com/en/articles/8797641-modrinth-app-storage-folder-location).
