# Java protection run — 2026-09-27

## Result

The repaired pipeline passed all 18 stages for `celeste-plus-20260927-auth-hardening-02`. It supersedes Java release `...-01`, which passed the earlier fixtures and was deployed, but then failed a real injection at the `RuntimeInstallationSigner.load` call boundary. The earlier checks did not cover that boundary. See [the overall run record](protection-run-20260927.md) for deployment/publication status.

**Fully restart Minecraft before retrying the repaired release.** Bootstrap A remains resident in the game JVM. Stopping modules, reopening the GUI, or restarting only the native launcher does not remove the old resident classes.

Current local evidence is under `build/module-qa/java-protected-candidate-20260927-04/`:

- `receipt.json`: input, producer, dependency, source, profile, output hashes, stage exit codes, and timings.
- `*.log`: individual build, protection, merge, and verification logs.
- `release/`: the five final candidate files listed below.
- `toolchain/`: the captured producer and its six dependencies.

These generated files are ignored build evidence, not committed release assets. Current receipt SHA-256: `f1ba5bd78a2e6e9a8ff9cb30558b82cd23cb5122c975b2e730be6e35d3ccf2ee`.

## Injection regression and repair

In run `...-03`, A's transformed host referred to an obfuscated return type in the `RuntimeInstallationSigner.load(Path, ClassLoader, byte[], String, long)` descriptor, while the stable delivery bridge retained its canonical declaration. The user hit the resulting missing-method error during injection. The original receipt remains historical build evidence; its passing fixture result did not establish working injection.

Profile A now keeps `dev.c4lam.celeste.auth.**` names. Their method bodies remain protected. The new artifact regression checks the actual host call descriptors against the stable `load` and delivery-constructor declarations, resolves all A class declarations and canonical auth classes, and invokes `load` against an empty temporary game directory. That invocation must reach the intentional missing-installation rejection rather than a linkage error.

The strengthened test fails against the broken protected `...-03` artifact with the user's exact descriptor mismatch. The raw artifact passes 398 checks. The repaired protected artifact passes 442 checks in each of three fresh JVM runs. After the repaired release was deployed at Fly version 130, the user fully restarted Minecraft, retried, and confirmed “Loads successfully.” This user confirmation is separate from the automated fixture checks and is not a broad gameplay or performance result.

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

The runner used profiles `02-bootstrap-a-implementation.json`, `03-bootstrap-b-compatible.json`, and `04-payload-protected-v2.json`, with the repaired auth-name boundary in profile A. They apply double composite string protection and strip debug information. A/B enable eligible implementation renaming; the auth interface names and payload class/member names remain fixed. A transformed 53 classes, renamed 48 class names, kept nine class names and protected 1,117 unique strings. Native extraction/virtualization, targeted auth native protection, bundled native virtualization, Crazy mode, resource relocation, and metadata relocation remain disabled in these Java profiles.

Stable host, delivery, memory, JNI wrapper, mixin, reflective serialization, and resource boundaries are retained by staging/merge rules and the repaired A auth-name keep. All 258 stable payload entries were byte-exact. The payload transformed 705 of 705 eligible implementation classes and protected 2,951 unique strings. It added 22 helpers and one vault. This run does not establish globally optimal settings or that all literals are hidden.

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
    --output build/module-qa/java-protected-candidate-20260927-04 `
    --release-id celeste-plus-20260927-auth-hardening-02 `
    --client-version 1.0.0-plus.2 `
    --base-url https://qb0f90cf11f4d1d52.fly.dev `
    --server-key rhLhkicLy3laX7_Q7rSv9OGVyrSHr1OyEZW8_hJfoKY `
    --offline
```

The origin and Ed25519 key above are existing public trust values; this operation did not authenticate to the service or use private signing keys. A rerun must select a fresh output directory because existing output is refused. Protection randomness can change output hashes between runs.

`parent-classpath.txt` is an explicit local fixture containing one existing JAR path per line. This run used 130 unique JARs from the existing Minecraft 1.21.11 / Fabric Loader 0.19.5 QA installation, filtered from its prior dependency list. It is not a portable checked-in classpath. The runtime compile target remains Minecraft 1.21.11 / Fabric Loader 0.19.3 / Fabric API 0.139.5+1.21.11. The receipt records every parent JAR hash. Reproduction on another machine requires an equivalent explicit dependency fixture and cached Gradle dependencies for `--offline`.

## Verification and timing

All 18 external stages exited successfully; their recorded durations total 23.87 seconds, excluding the earlier runtime remap. Checks included:

- Exact stable-byte preservation, package integrity, six resident support libraries, and parent linkage.
- 111 bootstrap helper compatibility assertions.
- Real Bootstrap A/B artifact loading, actual host-to-delivery call descriptors, canonical auth types, the missing-installation execution path, lifecycle ABI, inactive status, and rejection before activation when a release is absent. Baseline checks passed 398 assertions per run; protected checks passed 442.
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
| Baseline A/B | 0.3518, 0.3503, 0.3570 | 0.3518 s |
| Protected A/B | 0.4012, 0.4615, 0.4464 | 0.4464 s |

The measured median difference was about **94.6 ms**. These timings include JVM startup and the expanded cold verification fixture. They do not measure Minecraft startup, gameplay, FPS, or steady-state performance. They should not be compared directly with run `...-03`'s smaller test workload.

## Final candidate bytes

All repaired files are beneath `build/module-qa/java-protected-candidate-20260927-04/release/`. Native executable identity and registration are unchanged by this Java repair.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `beta-release.json` | 847 | `f68c3d94840bf88053aee2eb975bfd9d5b1eec142c2837e01ee0eb7584e714ab` |
| `bootstrap-a.jar` | 5,880,351 | `ad15884631b42026bab117b35b4edd355e6a6379a395ea377e2783778c135db7` |
| `bootstrap-b.bin` | 70,939 | `442b6183bddc94fa6392d0379809f6b331e29b0779324ffad0f5fb4776be3a2f` |
| `payload-a.bin` | 7,795,444 | `f4c274bc78c1589d729b83c7e978f26601a56c2c3021e4251de24e259dbbe29e` |
| `payload-b.bin` | 9,740,161 | `3eef1939b1888e6be83e1c5c8777ec1c357f343ac785dcdc9363a48a3fdee709` |

Private mapping sidecars remain outside the final release directory. The automated Java pipeline itself did not launch Minecraft, authenticate, register or deploy a release. The subsequent coordinated deployment and user-confirmed successful Load are recorded separately in the [overall release record](protection-run-20260927.md). Native identity and the explicitly authorized Windows unsigned status remain unchanged.
