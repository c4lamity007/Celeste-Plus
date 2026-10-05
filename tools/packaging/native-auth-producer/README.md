# Native auth producer source

This is the maintainable source delta for the reviewed Celeste+ native auth producer. It preserves the three-file producer patch, custody loader template and rewriter, and exact method selectors. The external obfuscator checkout and tool binaries are deliberately not vendored.

## Provenance and boundaries

- Original obfuscator bundle: `4424dca45dab369f27b28f4cc2f22e5ff13d3e11884494f058c6e1718703a644`.
- Original producer JAR: `6ea089553f06fd1e5d895a338a1cdd59d19a3dd4cbdbc4030e3d1de01551809b`.
- Original source receipt: `7ddc29ca0d8b52ee2aa08616979dcb01a585fa85004ecc9a7db68827e6840f2f`; all 286 baseline source hashes are retained in `provenance/producer-provenance.json`.
- Historical frozen native tool bundle: `b558f2e5f5741d5541ee80048052a95545708965103081b51856dd4732437369`. `provenance/reference-native-tool-bundle.json` is its metadata, not an assertion that this source-only directory contains that full bundle.
- `source-manifest.json` records the original and LF-normalized hash for every copied source/metadata file. Source line-ending normalization does not change the frozen binaries. The reviewed native prototype and sealed release candidates remain separate immutable evidence.

The patch adds explicit export/MAP output, exact selected-method JNI conversion, call-local JNI handling, and native-only mode. Native-only mode rejects Java transformations and preserves unselected method bodies and class initialization. Selectors list full owner/name/descriptors: update and review them when source or compiler-generated lambda names change. Missing selectors must fail; do not broaden to every method.

`CustodyLoadTemplate` delegates file custody to the parent `AuthNativeBridge` while keeping `System.load` inside the disposable child loader. `ReembedWithCustody` binds the final independently approved DLL hash and replaces only that exact mapped resource and its loader method. Its custody record is provenance, not virtualization approval.

This folder is outside `beta-lab` Java discovery and is not an implicit input to an already sealed Java release. A future producer build requires a fresh tool-bundle receipt and release approvals. Do not replace a historical `b558...` index with a new index under the same approval.

## Rebuild the source delta

Prerequisites: Windows x64, Git, Python 3.11+, the reviewed JDK 21 (the frozen classes were reproduced using Temurin 21.0.11+10), the externally retained exact baseline sources and seven toolchain JARs, and compiled reviewed Plus parent-contract classes containing `dev/celestebeta/host/AuthNativeBridge.class`. The native export stage additionally requires the installed MSVC toolchain. Nothing here installs or licenses tools.

Set these PowerShell variables to explicit local paths. `$taskOutput` must be fresh. `$taskBaseline` is read only; the commands copy only the three changed files.

```powershell
$taskSource = (Resolve-Path 'tools/packaging/native-auth-producer').Path
$taskBaseline = (Resolve-Path '<reviewed-baseline-source-directory>').Path
$taskToolchain = (Resolve-Path '<reviewed-toolchain-directory>').Path
$taskParentClasses = (Resolve-Path '<reviewed-parent-contract-classes>').Path
$taskJdk = (Resolve-Path '<reviewed-jdk-21-directory>').Path
$taskOutput = Join-Path (Get-Location) 'build/native-auth-producer-rebuild'
python "$taskSource/verify_inputs.py" --baseline-source "$taskBaseline" --toolchain "$taskToolchain"
if ($LASTEXITCODE -ne 0) { throw 'Input verification failed' }
if (Test-Path -LiteralPath $taskOutput) { throw 'Rebuild output must be fresh' }
$taskAdapted = Join-Path $taskOutput 'adapted-sources'
$taskClasses = Join-Path $taskOutput 'adapted-classes'
$taskIntegration = Join-Path $taskOutput 'integration/classes'
New-Item -ItemType Directory -Path $taskAdapted,$taskClasses,$taskIntegration | Out-Null
$taskManifest = Get-Content -LiteralPath "$taskSource/source-manifest.json" -Raw | ConvertFrom-Json
foreach ($taskEntry in $taskManifest.reconstructed_sources.PSObject.Properties) {
    $taskTarget = Join-Path $taskAdapted $taskEntry.Name
    New-Item -ItemType Directory -Path (Split-Path $taskTarget) -Force | Out-Null
    $taskText = [IO.File]::ReadAllText((Join-Path $taskBaseline $taskEntry.Name)).Replace("`r`n", "`n")
    [IO.File]::WriteAllText($taskTarget, $taskText, [Text.UTF8Encoding]::new($false))
}
git -C "$taskAdapted" apply --check "$taskSource/producer.patch"
if ($LASTEXITCODE -ne 0) { throw 'Patch precheck failed' }
git -C "$taskAdapted" apply "$taskSource/producer.patch"
if ($LASTEXITCODE -ne 0) { throw 'Patch apply failed' }
python "$taskSource/verify_inputs.py" --adapted-source "$taskAdapted"
if ($LASTEXITCODE -ne 0) { throw 'Reconstructed source mismatch' }
$taskClasspath = "$taskToolchain/word-obfuscator.jar;$taskToolchain/lib/*"
$taskJavaSources = @($taskManifest.reconstructed_sources.PSObject.Properties | ForEach-Object { Join-Path $taskAdapted $_.Name })
& "$taskJdk/bin/javac.exe" -encoding UTF-8 -cp "$taskClasspath" -d "$taskClasses" @taskJavaSources
if ($LASTEXITCODE -ne 0) { throw 'Producer compilation failed' }
& "$taskJdk/bin/javac.exe" -encoding UTF-8 -cp "$taskToolchain/lib/*;$taskParentClasses" -d "$taskIntegration" "$taskSource/integration/ReembedWithCustody.java" "$taskSource/integration/CustodyLoadTemplate.java"
if ($LASTEXITCODE -ne 0) { throw 'Custody integration compilation failed' }
```

Compare the 23 resulting producer class hashes with `compiled_overrides` in `adaptation-provenance.json`, and the two integration class hashes with the historical reference index. Rebuilding this preserved source using the stated JDK reproduced all 25 hashes exactly. This verifies producer reproducibility, not VMP protection or Minecraft behavior.

For a new release, use `tools/protect_auth_native_release.py` and its reviewed producer configuration. The required JVM properties are `celeste.prototype.methods=<exact-selector-file>`, `celeste.prototype.callLocalJni=true`, `celeste.prototype.nativeOnly=true`, and `celeste.prototype.export=<fresh-directory>`. Put `adapted-classes` before the baseline JAR and libraries. Do not run the stale prototype `check_native_only.py` or `freeze_bundle.py` copied from an old workspace; those were local experiments with absolute paths and are not production entry points.

The release path must check `tools/qa/NativeMethodParity.java` against the exact post-Java input and selected-method output, then pause for actual licensed VMProtect compilation, independent transformation review, JNI/protocol/lifecycle checks, and an explicitly reviewed DLL inventory/evidence index. Keep the ordinary 8 MiB per-entry limit. Only explicit independently reviewed native resources may use the authenticated budget: 32 MiB per DLL and 64 MiB aggregate, with exact size/hash/PE checks and matching bootstrap B/payload part A index. Only then reembed the approved DLLs and continue final packaging. No automatic license, VMProtect approval, artifact signing, publication or deployment is provided here.

## Final native-auth-02 verification fixture

The final second resume passed 41 protected native checks and 15 Java continuation stages. The exact reviewed DLL inventory and evidence index are approval-02; the final verification plan is `acdb3ed005c1511e60ee3648bf0ba0b2c8abeb242244b734cfb8fe9a76898b07`. See the [run record](../../../docs/native-auth-20260928.md) for result pins and the verified live rollout.

The source-only [lifecycle fixture recipe](lifecycle-fixture-queue/README.md) corrects a test race between revocation visibility and owner-thread cleanup queue publication. It rebuilds one test class and preserves all shutdown assertions and other fixture entries. It does not change selected methods, native output, production authorization ordering, or the historical producer bundle. Keep its source patch separate from an already sealed production checkpoint; a new fixture plan and reviewed evidence binding are required.
