# September 27: Windows media shutdown repair

## Status

`celeste-plus-20260927-media-cleanup-01` is prepared for a Java payload update.
Live publication is recorded below after deployment verification. The existing
protocol 2 Windows loader remains compatible; no launcher rebuild is required.

## Diagnosis

The user confirmed injection worked, then reported blank Media HUD and Sky Lyrics
and a roughly 30-second Stop delay. The published media DLL and all four JNI
wrapper classes were byte-identical to the preceding release, and the DLL was
loaded in the game. A separate owned JVM reproduced a stalled media enumeration;
a direct Windows API probe timed out requesting the media-session manager.

After the user closed the older stuck Fabric instance, both HUDs worked again.
The unchanged published DLL also resumed enumeration in the isolated probe.
This established recovery without replacing the playback DLL or changing auth.

The probe exposed a separate shutdown defect: the media support archive omitted
`CelesteMediaCleanup.dll`, whose authored source already exists in the pinned
core. Without it, the wrapper disabled cleanup and the owned JVM remained alive
after reaching `System.exit(0)`. Adding only that helper made both direct cleanup
and cleanup from a shutdown hook exit normally in about 0.6 seconds. A subsequent
three-case test also verified media reinitialization after cleanup in the same JVM.

## Change and verification

- The runtime input adds only the compiled cleanup DLL. All 972 prior archive
  entries are unchanged, including game code, JNI wrappers and playback DLL.
- Windows packaging now requires the paired x64 DLLs. Five focused packaging
  checks cover a missing helper, an orphan helper, malformed/wrong-architecture
  inputs, exact resource preservation and non-Windows support.
- `tools/build_media_cleanup.py` compiles the exact reviewed C++ source using
  installed MSVC/JDK headers and records the input, compiler, output and export.
- Runtime preparation supports either or both explicit DLL inputs without
  changing unrelated resources or child projects. Its suite passed 25 checks
  with one existing Windows symlink-permission skip, including real Gradle
  resource-copy fixtures for each combination.
- All 18 normal protected-Java stages passed. Protection runner guards passed
  nine checks. The actual final media support archive is separately tested with
  bounded owned JVMs; playback commands and song metadata are excluded. The final
  package passed direct cleanup (0.195 seconds), shutdown-hook cleanup (0.188
  seconds) and cleanup/reload (0.206 seconds), all with normal process exit.
- The tested support JAR exactly matches both protected Bootstrap A copies.
  A focused backend fixture decrypted and verified all four actual encrypted
  artifact bodies and rejected wrong recipients, replays and missing keys.

| Artifact | SHA-256 |
| --- | --- |
| Pinned cleanup C++ source | `efbb192a7f89e650e0c1aa1e9c194534e176b7af8f1bae773dc0c2567c90047d` |
| Cleanup DLL, 103,424 bytes | `8e5bd46ad34086a62b15db4793dded76e626c68b2c38d7aa54484dd4c8a2391b` |
| Remapped runtime input | `da7fda28ffe9acff3eb256c9b4951431e4357b6bfd6baee582b16afdb062ed84` |
| Java protection receipt | `179e0b416584bfa78e179f75bf94fe63b6c7a6e83dd75fe808dbd8cc5a3bfc17` |
| Protected runtime identity | `f9c1a06cadb05cdeaf54cdd8e5af15ab33abfc278db60b8bcf25021179e96ea0` |

This is a cleanup packaging repair. It does not replace Windows media services
or guarantee that every future OS media request will complete. The user confirmed
both HUDs on the preceding protocol 2 payload; final-payload Minecraft Stop/Load
remains a separate game check.

## Publication

Pending the Java-only rollout. The new image must inherit the exact Fly version
132 image, retain all 33 previous artifacts, add only the five new Plus catalog
files and change only the Plus release directory. Backend code, Standard's
release, TLS pins and native policy sequence 2 remain unchanged.
