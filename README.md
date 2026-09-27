# Celeste+

Celeste+ is the live-attach client. This is the development repository for its native launcher, Java 21 resident host (Bootstrap A), disposable payload bootstrap (Bootstrap B), game module updates, authentication integration, and regression tests. The normal mod-folder product is maintained separately in [Celeste](https://github.com/c4lamity007/Celeste).

Push Celeste+ work to **[c4lamity007/Celeste-Plus](https://github.com/c4lamity007/Celeste-Plus)**. The pinned Celeste core is an input dependency, not the destination for Plus changes. See [the current source and authentication handoff](docs/plus-source-transfer-20260927.md).

The host and launcher build independently of Minecraft, Fabric Loom, the authentication server, and a local Celeste checkout. A complete remapped Celeste runtime is an explicit packaging input. Authentication remains product-scoped to `celeste_plus`; no special UID or Owner license bypass is provided. Existing `beta` package names and wire schemas are retained for protocol compatibility.

## Build and test

Use Java 21 (with `JAVA_HOME` set), Python 3.11+, and the included Gradle wrapper. The Windows launcher requires stable Rust with the MSVC toolchain and Visual Studio C++ build tools. Dependency resolution needs network access on the first build; add `--offline` only when the caches are populated.

```powershell
.\gradlew.bat --no-daemon build
python -m unittest discover -s beta-lab/obfuscation -p test_prepare_obfuscation.py
cargo test --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml
cargo build --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml
```

Development, candidate CI and release verification currently target Windows x64 on the user's actual system. Linux and macOS implementations remain available for later work; their runtime behavior is unverified. A successful CI build does not establish desktop credential storage, attach behavior or gameplay results.

Outputs are ignored by Git:

- `build/module-qa/bootstrap-a/bootstrap-a.jar`
- `build/module-qa/bootstrap-b/bootstrap-b.bin`
- `beta-lab/native-launcher/target/release/CelestePlus.exe`

The default A/B artifacts are development outputs without a release trust/support contract. They are not assembled server releases. `check` runs explicit Java programs for A lifecycle, B lifecycle/loader retirement, resident transport, and signed delivery. The A transaction tests use synthetic Minecraft-shaped bytecode; the B tests use isolated fixtures. These checks do not establish in-game compatibility. The Java transport fixture attaches only to its own non-Minecraft test process.

For a separate offline hook/schema gate, supply a named Minecraft 1.21.11 jar
explicitly: `./gradlew betaMinecraftSchemaCheck -PminecraftSchemaJar=/path/to/named.jar`.
That task fails when the input is missing and reads class bytes without launching
Minecraft. Default host builds never search a local game cache.

Two additional native integration checks require explicit Java fixture paths and never attach to a game. After the Java build:

```powershell
$env:CELESTE_CONTROL_TEST_JAVA = Join-Path $env:JAVA_HOME 'bin/java.exe'
$env:CELESTE_ATTACH_TEST_JAVA = $env:CELESTE_CONTROL_TEST_JAVA
$env:CELESTE_CONTROL_TEST_CLASSPATH = (Resolve-Path 'build/classes/java/test').Path + [IO.Path]::PathSeparator + (Resolve-Path 'build/classes/java/main').Path
$env:CELESTE_ATTACH_TEST_JAR = (Resolve-Path 'build/module-qa/bootstrap-a/bootstrap-a.jar').Path
cargo test --locked --release --manifest-path beta-lab/native-launcher/Cargo.toml actual_ -- --ignored
```

## Runtime dependency

`core.lock.json` pins the normal Celeste Git commit and the manifest hash of `runtime-overlay/`. The overlay contains the current Celeste+ modules, HUDs, runtime authentication, shaders, fonts, and required HUD assets. Applying it reproduces the latest development game source. It is **not byte-identical to the published protected runtime**. No imported models, native binaries, authentication database, or signing credentials are included.

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

## Authentication and publication

The current Windows release is
[`celeste-plus-20260927-auth-v2-01`](https://github.com/c4lamity007/Celeste-Plus/releases/tag/celeste-plus-20260927-auth-v2-01),
deployed and verified at Fly version **132** with native policy sequence **2**.
It adds pinned transport policies, encrypted delivery, hardware/process session
binding and grant rotation. **Use the new loader and fully restart Minecraft.**
The executable remains Authenticode unsigned under the user's accepted
**Unknown publisher** preference. All 14 selected VMProtect paths and all three
published asset downloads passed verification. The user is handling the new
Minecraft loading check; it is not yet recorded as passed. See the
[release record](docs/releases/20260927-auth-v2.md) and
[protocol 2 implementation and rollback record](docs/auth-protocol-v2-20260927.md).

The complete [authentication, Java obfuscation and VMProtect runbook](tools/packaging/README.md)
documents the build order, selective protection settings, tool usage, verification,
new-loader requirements and coordinated publication flow.

`auth-service/` contains the coordinated shared backend source required by this Plus revision, including live entitlement/device validation, request proof binding, nonce-bound delivery, token revocation, and native publication manifests. It still serves both product identities; changing the source repository does not create a second service or change its live configuration. The Plus launcher, host, and runtime require the `celeste_plus` entitlement. See [the auth implementation record](docs/auth-hardening-20260927.md).

The full backend and repaired Java release `celeste-plus-20260927-auth-hardening-02` were first deployed and verified at Fly version 130. The protected native release `...-01` is registered at sequence 1; seven targets passed exact-artifact coverage verification. **Fully restart Minecraft before loading the repair**: the old resident Bootstrap A persists until the game JVM exits. The user confirmed “Loads successfully” after restarting and retrying. The [Windows loader release](https://github.com/c4lamity007/Celeste-Plus/releases/tag/celeste-plus-20260927-auth-hardening-01) is published and all asset downloads have verified hashes. The user explicitly accepted **Unknown publisher** for these reviewed Windows bytes; the executable remains **Authenticode unsigned**. Ordinary unsigned CI outputs remain test candidates. See the [release run record](docs/protection-run-20260927.md) for hashes, the earlier injection failure and remaining verification limits.

The preceding payload was [`celeste-plus-20260927-double-anchor-01`](docs/releases/20260927-double-anchor.md), deployed and verified at Fly version **131**. It introduced Double Anchor's same-tick explosion and replacement, followed by glowstone and totem. The existing loader was compatible with that earlier release; protocol 2 now requires its new loader. The game build, 336 sequence checks, all 18 protection stages and 95 auth-delivery tests passed for that revision; live gameplay remained unverified.

Offline backend and packaging checks:

```powershell
python -m pip install -r auth-service/requirements.txt
python -m unittest discover -s tools/qa -p 'test_*.py'
python -m unittest discover -s beta-lab/release-package -p 'test_*.py'
Set-Location auth-service
python -m pytest tests -q
```

## Lifetime and compatibility

A remains resident in the selected JVM and reuses only exact verified support libraries and bridges. Each successful lifetime creates fresh B/payload loaders. Resource URLs, connections and streams are revoked at Stop and release their loader ownership, even if a caller keeps those handles. Cancelled control deadlines leave the scheduler promptly, and failed starts discard only their verified, unused support files. Owned client screens close before successful payload retirement, including model import and the ordinary inventory close lifecycle. Clean stop waits for resource and asynchronous worker retirement before releasing their references; failed cleanup requires a fresh game process. JVM garbage collection decides when eligible classes unload, so this is not a promise of forced class unloading. Stop does not restore the complete JVM to an earlier snapshot or remove all strings and resident classes. The retention regression keeps old session/resource handles reachable across 32 lifetimes and verifies disposable-loader collection in an isolated test JVM; production Stop does not request garbage collection.

The current hook target is Minecraft 1.21.11 on compatible Fabric clients. Launcher discovery does not imply loader or modpack compatibility. Quilt, Forge, and NeoForge are not established supported targets. Test each exact loader/mod combination and protected release before distribution.

`tests/source-provenance.json` records the original source-only split inputs. Test-only core fixture snapshots are never packaged in A or B. Generated releases, mappings, caches, credentials, and local runtime outputs must remain untracked.
