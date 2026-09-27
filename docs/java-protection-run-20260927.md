# Java protection run — 2026-09-27

## Result

The actual Java protection pipeline passed all 18 stages for `celeste-plus-20260927-auth-hardening-01`. The receipt status is `protected-verified-candidate-not-published`, with `deployed: false`. This record covers Bootstrap A, Bootstrap B, and both Java payload parts. Native protection and production publication have separate requirements; see [the overall run record](protection-run-20260927.md).

Local evidence is under `build/module-qa/java-protected-candidate-20260927-03/`:

- `receipt.json`: input, producer, dependency, source, profile, output hashes, stage exit codes, and timings.
- `logs/`: individual build, protection, merge, and verification logs.
- `release/`: the five final candidate files listed below.
- `toolchain/`: the captured producer and its six dependencies.

These generated files are ignored build evidence, not committed release assets. Receipt SHA-256: `c1f42d2f1577c7c0e4363f36e6e24ffa890d0212e0494bb145d01be74be43402`.

## Producer and settings

The existing producer was used read-only from:

```text
C:\Users\c4lam\.codex\worktrees\20bc\Obfuscator\build\auth-instruction-release-v2-20260926\word-obfuscator.jar
```

Its entry point is `dev.local.obfuscator.App`; its supported command is `java -jar word-obfuscator.jar --config <profile.json>`. Running it without arguments opens its GUI. The six manifest dependencies are ASM 9.10.1 (`asm`, `asm-tree`, `asm-commons`, `asm-analysis`, `asm-util`) and Gson 2.11.0.

| Pin | SHA-256 |
| --- | --- |
| Producer JAR | `6ea089553f06fd1e5d895a338a1cdd59d19a3dd4cbdbc4030e3d1de01551809b` |
| Entire producer bundle | `4424dca45dab369f27b28f4cc2f22e5ff13d3e11884494f058c6e1718703a644` |
| Remapped runtime input | `03dcc6b32b34b32c41574476b04ce55121afd5fe04ab8aa750500c063237a39d` |
| Runtime overlay manifest | `d2e5c8b94f090b6b33c58d8b8bc458aa6140aaea40c519c1a5244b1d7ed90260` |

The bundle pin covers the canonical filename/hash map of the JAR and all six declared libraries. The producer's existing `build/auth-instruction-evidence-final-v2-20260926.json` listed 286 source hashes; all matched the inspected source. No foreign obfuscator source was changed.

The runner used the existing profiles `02-bootstrap-a-implementation.json`, `03-bootstrap-b-compatible.json`, and `04-payload-protected-v2.json`. They apply double composite string protection and strip debug information. A/B enable eligible implementation renaming; payload class and member names remain fixed. Native extraction/virtualization, targeted auth native protection, bundled native virtualization, Crazy mode, resource relocation, and metadata relocation remain disabled in these Java profiles.

Stable host, delivery, memory, JNI wrapper, mixin, reflective serialization, and resource boundaries are retained by the existing staging/merge rules. All 258 stable payload entries were byte-exact. The payload transformed 705 of 705 eligible implementation classes and protected 2,951 unique strings. It added 22 helpers and one vault. These are the tested compatible settings; this run does not establish that they are globally optimal or that all literals are hidden.

## Actual commands

Commands ran from the Celeste-Plus repository root on Windows with Eclipse Temurin JDK `21.0.11+10`. The runtime had already been materialized under `build/runtime-core` using the pinned core and verified overlay. The first command completed in 14 seconds:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
Push-Location build/runtime-core
try {
    .\gradlew.bat --no-daemon --offline :remapJar
    if ($LASTEXITCODE -ne 0) { throw 'Runtime remap failed' }
} finally {
    Pop-Location
}
```

The local Python interpreter used was the existing sibling checkout's Python environment. An equivalent Python 3 interpreter can run the repository runner. The producer and dependency fixture below must exist; the runner does not fabricate or download a replacement producer.

```powershell
$javaProtectionPython = 'C:\Users\c4lam\Downloads\New folder (5)\Celeste - Updated\auth-service\.venv\Scripts\python.exe'
$javaProtectionProducer = 'C:\Users\c4lam\.codex\worktrees\20bc\Obfuscator\build\auth-instruction-release-v2-20260926\word-obfuscator.jar'

& $javaProtectionPython tools/protect_java_release.py inspect-tool `
    --obfuscator $javaProtectionProducer

& $javaProtectionPython tools/protect_java_release.py run `
    --input build/runtime-core/build/libs/celeste-client-1.0.0.jar `
    --obfuscator $javaProtectionProducer `
    --tool-sha256 4424dca45dab369f27b28f4cc2f22e5ff13d3e11884494f058c6e1718703a644 `
    --parent-classpath build/module-qa/java-release-inputs/parent-classpath.txt `
    --java-home 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot' `
    --output build/module-qa/java-protected-candidate-20260927-03 `
    --release-id celeste-plus-20260927-auth-hardening-01 `
    --client-version 1.0.0-plus.2 `
    --base-url https://qb0f90cf11f4d1d52.fly.dev `
    --server-key rhLhkicLy3laX7_Q7rSv9OGVyrSHr1OyEZW8_hJfoKY `
    --offline
```

The origin and Ed25519 key above are existing public trust values; this operation did not authenticate to the service or use private signing keys. A rerun must select a fresh output directory because existing output is refused. Protection randomness can change output hashes between runs.

`parent-classpath.txt` is an explicit local fixture containing one existing JAR path per line. This run used 130 unique JARs from the existing Minecraft 1.21.11 / Fabric Loader 0.19.5 QA installation, filtered from its prior dependency list. It is not a portable checked-in classpath. The runtime compile target remains Minecraft 1.21.11 / Fabric Loader 0.19.3 / Fabric API 0.139.5+1.21.11. The receipt records every parent JAR hash. Reproduction on another machine requires an equivalent explicit dependency fixture and cached Gradle dependencies for `--offline`.

## Verification and timing

All 18 external stages exited successfully; their recorded durations total 19.37 seconds, excluding the earlier runtime remap. Checks included:

- Exact stable-byte preservation, package integrity, six resident support libraries, and parent linkage.
- 111 bootstrap helper compatibility assertions.
- Real Bootstrap A/B artifact loading, fixed entry points and lifecycle ABI, inactive status, and rejection before activation when a release is absent. Baseline checks passed 16 assertions per run; protected checks passed 38.
- All 783 packaged payload classes loaded through the real retained two-part class loader with `-Xverify:all`; their declared methods, constructors, and fields resolved without static initialization.
- Revalidation of the producer bundle, input, 82 source inputs, generated profiles, parent dependencies, and final artifacts.

The runner's focused Python tests passed **9 tests**:

```powershell
& $javaProtectionPython -m unittest discover -s tests -p test_protect_java_release.py
```

Those tests cover complete bundle hashing, immutable archive manifest capture, unsafe dependency references, wrong-pin refusal before execution, existing-output preservation, profile boundary restrictions, parent dependency validation, and a failed external stage producing a failure receipt without a release claim. `git diff --check` passed for the added runner/check files.

Three fresh JVM processes were measured for each bootstrap fixture, using `-Xverify:all`:

| Fixture | Runs (seconds) | Median |
| --- | --- | --- |
| Baseline A/B | 0.2311, 0.2321, 0.2334 | 0.2321 s |
| Protected A/B | 0.2950, 0.2926, 0.3033 | 0.2950 s |

The measured median difference was about **62.9 ms**. These timings include JVM startup and execute a cold verification fixture. They do not measure Minecraft startup, gameplay, FPS, or steady-state performance.

## Final candidate bytes

All files are beneath `build/module-qa/java-protected-candidate-20260927-03/release/`.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `beta-release.json` | 847 | `5de7072ab0ee3964b4cd7d4b9b1e4b3b2db635926ea6542c32be90b77569f817` |
| `bootstrap-a.jar` | 5,878,022 | `f123365d475499dc9fa1e26fb0fbd2973afc81fb89abfecfc10f3c566164e366` |
| `bootstrap-b.bin` | 70,170 | `5b45a9136a52d5885d8865dd4268bfc20d191ef935e1c72357ab927149a33e87` |
| `payload-a.bin` | 7,788,844 | `038b3431a8fc7a2ba2a78a8ff16333e1b976bc7373ad377cd4336b149f771e67` |
| `payload-b.bin` | 9,731,851 | `8cb77f6d6876d8e81e1bf74c8b0851d99edf4256e445de0f59d963bc2e56fcb1` |

Private mapping sidecars remain outside the final release directory. No Minecraft process was launched or attached, no live authentication was attempted, and this run did not sign native binaries, register a release, upload artifacts, or deploy production. Native protection evidence and protected/signature gates remain required for the complete product release.
