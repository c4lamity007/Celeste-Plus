# Celeste+

Celeste+ is the live-attach client. This repository contains its Windows launcher, Java 21 resident host (Bootstrap A), disposable payload bootstrap (Bootstrap B), hook adapters, delivery verification, and isolated regression tests. The normal mod-folder product is maintained separately in [Celeste](https://github.com/c4lamity007/Celeste).

The host and launcher build independently of Minecraft, Fabric Loom, the authentication server, and a local Celeste checkout. A complete remapped Celeste runtime is an explicit packaging input. Authentication remains product-scoped to `celeste_plus`; no special UID or Owner license bypass is provided. Existing `beta` package names and wire schemas are retained for protocol compatibility.

## Build and test

Use Java 21 (with `JAVA_HOME` set), Python 3.10+, and the included Gradle wrapper. The Windows launcher requires stable Rust with the MSVC toolchain and Visual Studio C++ build tools. Dependency resolution needs network access on the first build; add `--offline` only when the caches are populated.

```powershell
.\gradlew.bat --no-daemon build
python -m unittest discover -s beta-lab/obfuscation -p test_prepare_obfuscation.py
cargo test --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml
cargo build --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml
```

On other platforms, `./gradlew` builds and checks the Java host. The launcher uses Windows APIs and is not a cross-platform executable.

Outputs are ignored by Git:

- `build/module-qa/bootstrap-a/bootstrap-a.jar`
- `build/module-qa/bootstrap-b/bootstrap-b.bin`
- `beta-lab/native-launcher/target/release/CelestePlus.exe`

The default A/B artifacts are development outputs without a release trust/support contract. They are not assembled server releases. `check` runs explicit Java programs for A lifecycle, B lifecycle/loader retirement, resident transport, and signed delivery. The A transaction tests use synthetic Minecraft-shaped bytecode; the B tests use isolated fixtures. These checks do not establish in-game compatibility. The Java transport fixture attaches only to its own non-Minecraft test process.

Two additional native integration checks require explicit Java fixture paths and never attach to a game. After the Java build:

```powershell
$env:CELESTE_CONTROL_TEST_JAVA = Join-Path $env:JAVA_HOME 'bin/java.exe'
$env:CELESTE_ATTACH_TEST_JAVA = $env:CELESTE_CONTROL_TEST_JAVA
$env:CELESTE_CONTROL_TEST_CLASSPATH = (Resolve-Path 'build/classes/java/test').Path + [IO.Path]::PathSeparator + (Resolve-Path 'build/classes/java/main').Path
$env:CELESTE_ATTACH_TEST_JAR = (Resolve-Path 'build/module-qa/bootstrap-a/bootstrap-a.jar').Path
cargo test --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml actual_ -- --ignored
```

## Runtime dependency

`core.lock.json` pins the normal Celeste Git commit and the manifest hash of `runtime-overlay/`. The overlay contains the current Celeste+ development source changes and two shader sources, not a complete copy of Celeste. It is **not byte-identical to the published protected runtime**. No imported models, native binaries, authentication database, or signing credentials are included.

Supply an accessible checkout of the exact pinned core commit:

```powershell
python tools/prepare_runtime.py --core-checkout ../Celeste
python -m unittest discover -s tests -p test_prepare_runtime.py
```

The helper verifies the pin and overlay hashes, then creates a fresh `build/runtime-core`. It refuses existing output and never deletes it automatically. To also build the full remapped runtime, use `--build`; if the core requires an externally supplied media library, add `--media-dll <path-to-MediaPlayerInfo.dll>`. The resulting packaging input is `build/runtime-core/build/libs/celeste-client-1.0.0.jar`. Follow the pinned core's requirements for licensed dependencies and assets.

## Local package validation

The packaging tool accepts a complete supplied remapped core JAR. Descriptor encoding stays in the pinned core's existing `tools/package_final_release.py`; it is loaded through the explicit `--descriptor-module` path and is not duplicated in this project.

A fixture-only smoke package needs no deployment credentials:

```powershell
python beta-lab/release-package/package_beta.py prepare --input build/runtime-core/build/libs/celeste-client-1.0.0.jar --descriptor-module build/runtime-core/tools/package_final_release.py --output build/packages/smoke --release-id local-smoke --client-version 1.0.0 --validation-only
python tools/validate_package.py --package build/packages/smoke
.\gradlew.bat --no-daemon betaBootstrapAJar betaBootstrapBJar "-PbetaPackageDirectory=build/packages/smoke" "-PbetaLayoutFile=build/packages/smoke/runtime-layout.json"
```

Validation checks both retained payload parts, the canonical integrity digest, and exact resident support bytes without executing the payload entrypoint. Add `--parent-classpath <text-file>` to verify game/library linkage; that file contains one existing dependency JAR path per line. Validation without this option does not check those external classes. Fixture-only packages are explicitly rejected by final release assembly.

For a release, prepare a fresh directory with the authorized HTTPS service origin and its public verification key (`--base-url`, `--server-key`, without `--validation-only`), validate it, and build matching A/B artifacts with the two Gradle properties above. If protecting the payload, preserve its stable boundaries and supply the mapped lifecycle names reported by `payload-entrypoint` when preparing the final package.

`beta-lab/obfuscation/prepare_obfuscation.py` stages/merges byte-preserving boundaries. `configure_release_profiles.py --release-id <id>` generates fresh bootstrap protection configs; `--scope all --parent-classpath <file>` also configures payload protection with explicitly supplied dependencies. These helpers do not run an obfuscator; the approved external protection tool is a separate build prerequisite. The entire attach entry package, including `ResidentControl`, stays in A's stable pass so the JNI/Attach API entry boundary has no generated decoder dependency.

After protection and validation, assemble the exact matched files:

```powershell
python beta-lab/release-package/package_beta.py assemble --package <prepared-release> --bootstrap-a <final-bootstrap-a.jar> --bootstrap-b <final-bootstrap-b.bin> --output <fresh-release-directory>
```

The five-file output contains A, B, two payload parts, and `beta-release.json`. Assembly verifies contract and digest agreement; it neither signs server responses nor deploys. Server configuration, product entitlements, release approval, and in-game verification remain separate release steps.

## Lifetime and compatibility

A remains resident in the selected JVM and reuses only exact verified support libraries and bridges. Each successful lifetime creates fresh B/payload loaders. Clean stop waits for resource and asynchronous worker retirement before releasing their references; failed cleanup requires a fresh game process. JVM garbage collection decides when eligible classes unload, so this is not a promise of forced class unloading.

The current hook target is Minecraft 1.21.11 on compatible Fabric clients. Launcher discovery does not imply loader or modpack compatibility. Quilt, Forge, and NeoForge are not established supported targets. Test each exact loader/mod combination and protected release before distribution.

`tests/source-provenance.json` records the original source-only split inputs. Test-only core fixture snapshots are never packaged in A or B. Generated releases, mappings, caches, credentials, and local runtime outputs must remain untracked.
