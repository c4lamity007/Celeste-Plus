# Windows auth recovery and Ultra loader

Release ID: `celeste-plus-20260927-auth-recovery-01`.

Status: [published and verified](https://github.com/c4lamity007/Celeste-Plus/releases/tag/celeste-plus-20260927-auth-recovery-01).
The matching backend and protected Java are live at Fly version **134**; signed
native policy sequence **3** includes the reviewed loader and retains both prior
hashes. All three published asset bodies were downloaded and verified.

Source commit: `beaafbb250ca613926e2e865a83fe1b59f250818` in **Celeste-Plus**.
Deployed image digest:
`sha256:ec8ea205a2fd2a0d81bd7a1126e717d2c070cc96d8d94230a4ed6640ecb13fea`.
Live verification covered all 26 backend files, 43 artifact files, preserved
Standard configuration/server trust, fresh signed policies and unauthorized
download rejection. A private online SQLite backup passed its integrity check
before deployment; no license reset or database rollback was performed.

## Changes

- Every Plus artifact download requires a hardware identity bound into its
  installation-key proof. The server checks the registered identity before and
  after artifact processing. Standard product behavior is unchanged.
- The runtime supervises its heartbeat worker and restarts a terminated worker
  under bounded recovery rules. A restart cannot renew the server-issued lease.
- Windows hardware and environment checks have firmware/native fallbacks for
  WMI failures. The fallback retained identity parity on the development PC.
- Auth strings have wider obfuscation coverage. An allocation-free Windows TLS
  callback performs an early debugger check; ordinary startup verifies it ran.
- An acknowledged cleanup process removes owned plaintext Bootstrap A files
  after the selected game exits, including after the loader closes. File identity,
  authenticated receipts and deletion by handle prevent unrelated file removal.
- The loader's 14 selected authorization functions use VMProtect Ultra at 100%
  complexity, with import, memory, resource, packing, debugger and VM options
  enabled. See the [profile](../../tools/packaging/profiles/windows-loader-ultra.md).

The [implementation record](../auth-recovery-and-download-binding.md) covers
recovery limits, interrupted writes, persistent file locks and rollback coupling.
No release automation, renewal UI or payment-provider work is included.

## Release identities

| Item | SHA-256 |
| --- | --- |
| Native input, 7,762,944 bytes | `e83a174e14e4c6631897326fa80e35cad417cf1fc2aa33bbabb66588e63f2b3b` |
| Ultra loader, 68,233,216 bytes | `31ce0fd052af152e5fe340847eece882bceeb11dd65414a70ac0452ab2dfd743` |
| Actual GUI project | `686f9de7e2fb741def07662e673b9560df4f8d0156ab3295cd1e7f62869404b1` |
| Actual compile evidence | `8de78894c914201ae2254fdff7e926acdee23e51f7315b2134c09004fb6d8156` |
| Native protection receipt | `cf23a45d5e2c83cb2198b95e2ccd8df5a9308a4bf1efc07d7cca77588d8adafb` |
| Native verification report | `3091750c4500fca947aed70aef1951455e34fb6a83166215809393e87c326b17` |
| Private review index | `ae5fc6d0410593b79c9206ef45e8e873bf08cdfc5308ad0f91e2b611f3e4b5f9` |
| Canonical LF native verifier | `371c98b05e77d45604022b56b4ea6106f5c9f302e33c744a780e8812410f4730` |
| Java protection receipt | `f55f911ff9437f376e0fc483b61865af6f06cb0894423914ba69643f2a05e7e0` |
| Bootstrap A | `73a47e8d4b0df1a24ee936ef2ca60a0d0353e4c0ec71903fbaa981c9cfd88df3` |
| Live sequence 3 verification | `408bfe28114160a441b24d1c665392827ca5ba0effee88a8861d174cc041e46b` |
| GitHub publication record | `e77f1a59b44fdd577c503b61739da1a6a8fae3f542d09db8e659de696ba1d9b3` |
| Actual asset download verification | `b68f10fd1e82dd469b667aaa92de45f1036a2f1fbf2ba6009edb8a3ec9c1b5a6` |

## Completed checks

- Backend: 200 focused checks passed, one skipped.
- Native: 122 checks passed, eight optional checks ignored; eight cache checks
  and a warnings-denied release build also passed after the final string changes.
- Actual Windows cleanup: all eight cases passed on both final input and Ultra
  output, including launcher exit, duplicate helpers, invalid receipts, partial
  writes and replacement files.
- Java protection: all 18 stages passed, including three isolated runs of 1,265
  protected-bootstrap checks. The final protected A's hardware identity matched
  the native reader and reused its cache correctly. The installation signer ABI
  and both Windows media DLLs remain unchanged.
- Early callback registration matches the compiler MAP. Release-only metadata
  checks found no debug records, personal path prefix or test probe.
- The upgraded VMProtect Control MCP passed 115 tests with two platform skips.
- All 14 original entries were independently reviewed through loaded VM operand
  decoding and dispatch. The exact verifier passed 20 positive/negative checks,
  with historical verifiers preserved. This is bounded code evidence, not an
  execution trace or proof of every path through the program.
- The staged backend passed 18 additional checks with the actual protected Java
  catalog, including encrypted artifact round trips and Standard/Plus isolation.

Five fresh-process samples on this PC measured the protected debugger gate and
cleanup acknowledgement at median 3.27 seconds, p95 3.44 seconds; the unprotected
input measured 0.027 and 0.028 seconds. Windows file caches were not flushed.
This measures that startup path only, not login, injection or gameplay. Maximum
complexity substantially increases loader size and startup work.

The executable is Authenticode unsigned under the user's accepted **Unknown
publisher** preference. Server signatures remain required. No paid tool upgrade
or certificate purchase is required for this release path.

## Coordinated rollout

The deployed backend requires this loader and Bootstrap A together. Old Plus loaders
do not send the new artifact hardware proof. Use the new loader and **fully
restart Minecraft** before loading; existing resident Bootstrap A cannot be
replaced inside a running game.

Keep the previous image, catalog and signed native policy for rollback. Restore
the compatible image/catalog and publish a higher native policy sequence; never
restore an old license database or reset customer registrations.

Final released-binary account activation, Minecraft attachment, Stop/Load and
gameplay remain user verification. Compilation, source fixtures and code review
do not establish these runtime results.
