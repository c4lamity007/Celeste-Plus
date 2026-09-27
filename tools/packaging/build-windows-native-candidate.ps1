param(
    [Parameter(Mandatory = $true)][ValidateSet('celeste_plus')][string]$Product,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$Python = 'python',
    [switch]$MapForProtection
)

$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw 'Choose a fresh candidate output directory.' }
if (-not $IsWindows) { throw 'This candidate builder requires Windows PowerShell 7 and MSVC.' }
if ($env:RUSTFLAGS -or $env:CARGO_ENCODED_RUSTFLAGS) { throw 'Run with RUSTFLAGS and CARGO_ENCODED_RUSTFLAGS unset; the candidate flags must be explicit.' }
$taskManifest = 'beta-lab/native-launcher/Cargo.toml'
$taskExecutable = 'CelestePlus.exe'
$taskPublicName = 'Celeste+.exe'
$taskSysroot = (& rustc --print sysroot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the Rust sysroot.' }
$taskStrip = Join-Path $taskSysroot 'lib/rustlib/x86_64-pc-windows-msvc/bin/llvm-strip.exe'
if (-not (Test-Path -LiteralPath $taskStrip -PathType Leaf)) { throw 'Install the Rust llvm-tools-preview component before building.' }
$taskPython = (Get-Command $Python -ErrorAction Stop).Source
$taskUserRoot = [Environment]::GetFolderPath('UserProfile')
$taskCargoRoot = if ($env:CARGO_HOME) { [IO.Path]::GetFullPath($env:CARGO_HOME) } else { Join-Path $taskUserRoot '.cargo' }
$taskFlags = @('--remap-path-prefix', "$taskUserRoot=/user", '--remap-path-prefix', "$taskCargoRoot=/cargo", '--remap-path-prefix', "$taskRoot=/src", '-C', 'link-arg=/DEBUG:NONE')
$taskEnvironmentNames = @('CARGO_ENCODED_RUSTFLAGS','CARGO_PROFILE_RELEASE_DEBUG','CARGO_PROFILE_RELEASE_DEBUG_ASSERTIONS')
$taskOldEnvironment = @{}
foreach ($taskName in $taskEnvironmentNames) { $taskOldEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process') }
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskTarget = Join-Path $taskOutput 'cargo-target'
$taskBinary = Join-Path $taskOutput $taskPublicName
$taskLinkerMap = Join-Path $taskOutput 'Celeste+.map'
function Get-CandidateSourceHashes {
    $taskSourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'shared-auth'),(Join-Path $taskRoot 'beta-lab/native-shared/src') -Recurse -File -Filter '*.rs')
    if ($Product -eq 'celeste_plus') { $taskSourceFiles += @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'beta-lab/native-launcher/src') -Recurse -File -Filter '*.rs') }
    $taskManifestPath = Join-Path $taskRoot $taskManifest
    $taskSourceFiles += Get-Item -LiteralPath $taskManifestPath,(Join-Path ([IO.Path]::GetDirectoryName($taskManifestPath)) 'Cargo.lock'),$PSCommandPath,(Join-Path $PSScriptRoot 'windows_release_metadata.py')
    $taskSourceFiles += Get-Item -LiteralPath (Join-Path $taskRoot 'beta-lab/native-launcher/assets/celeste-logo.png')
    @($taskSourceFiles | Sort-Object FullName -Unique | ForEach-Object { [ordered]@{path=[IO.Path]::GetRelativePath($taskRoot,$_.FullName).Replace('\','/'); sha256=(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()} })
}
$taskInputs = @(Get-CandidateSourceHashes)
$taskInitialStamp = $taskInputs | ConvertTo-Json -Depth 4 -Compress
Push-Location $taskRoot
try {
    $env:CARGO_ENCODED_RUSTFLAGS = $taskFlags -join [char]31
    $env:CARGO_PROFILE_RELEASE_DEBUG = 'false'
    $env:CARGO_PROFILE_RELEASE_DEBUG_ASSERTIONS = 'false'
    if ($MapForProtection) {
        # Apply MAP arguments only to the final launcher link, never dependency
        # build scripts. Keep this address/name file private; it is not a release asset.
        & cargo rustc --locked --release --target x86_64-pc-windows-msvc --target-dir $taskTarget --manifest-path $taskManifest --bin CelestePlus -- -C "link-arg=/MAP:$taskLinkerMap" -C 'link-arg=/MAPINFO:EXPORTS' *> (Join-Path $taskOutput 'build.log')
    } else {
        & cargo build --locked --release --target x86_64-pc-windows-msvc --target-dir $taskTarget --manifest-path $taskManifest *> (Join-Path $taskOutput 'build.log')
    }
    if ($LASTEXITCODE -ne 0) { throw 'Cargo build failed; see the candidate build.log.' }
    if ($MapForProtection -and (-not (Test-Path -LiteralPath $taskLinkerMap -PathType Leaf) -or (Get-Item -LiteralPath $taskLinkerMap).Length -eq 0)) { throw 'The final linker did not emit a protection MAP.' }
    $taskCompiled = Join-Path $taskTarget "x86_64-pc-windows-msvc/release/$taskExecutable"
    Copy-Item -LiteralPath $taskCompiled -Destination $taskBinary
    $taskBefore = (Get-FileHash -LiteralPath $taskBinary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-AuthenticodeSignature -LiteralPath $taskBinary).Status -ne 'NotSigned') { throw 'Strip before signing; this builder does not modify a signed executable.' }
    & $taskStrip --strip-debug --strip-all $taskBinary *> (Join-Path $taskOutput 'strip.log')
    if ($LASTEXITCODE -ne 0) { throw 'LLVM stripping failed.' }
    & $taskPython tools/packaging/windows_release_metadata.py --binary $taskBinary --strip-reviewed-debug *> (Join-Path $taskOutput 'strip-pe-debug.json')
    if ($LASTEXITCODE -ne 0) { throw 'PE debug metadata removal failed; see strip-pe-debug.json.' }
    & $taskPython tools/packaging/windows_release_metadata.py --binary $taskBinary --forbid-prefix $taskRoot --forbid-prefix $taskUserRoot --forbid-prefix $taskCargoRoot *> (Join-Path $taskOutput 'metadata.json')
    if ($LASTEXITCODE -ne 0) { throw 'Candidate still contains prohibited metadata; see metadata.json.' }
    $taskMetadata = Get-Content -LiteralPath (Join-Path $taskOutput 'metadata.json') -Raw | ConvertFrom-Json
    $taskAfter = (Get-FileHash -LiteralPath $taskBinary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskMetadata.sha256 -ne $taskAfter -or -not $taskMetadata.passed) { throw 'Candidate changed during metadata verification.' }
    $taskCompilerVersion = (& rustc --version).Trim()
    if ($taskInitialStamp -ne (@(Get-CandidateSourceHashes) | ConvertTo-Json -Depth 4 -Compress)) { throw 'Sources changed during the build; this candidate cannot verify the current source snapshot.' }
    [ordered]@{
        schema='celeste-windows-metadata-candidate-1'
        product=$Product
        artifact=$taskPublicName
        compiler=$taskCompilerVersion
        target='x86_64-pc-windows-msvc'
        beforeStripSha256=$taskBefore
        sha256=$taskAfter
        size=(Get-Item -LiteralPath $taskBinary).Length
        llvmStripSha256=(Get-FileHash -LiteralPath $taskStrip).Hash.ToLowerInvariant()
        sourceFiles=$taskInputs
        privateMapSha256=$(if ($MapForProtection) { (Get-FileHash -LiteralPath $taskLinkerMap -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null })
        status='unsigned-local-candidate'
        metadataCheck='passed'
        limitations=@('not Authenticode-signed','not native-virtualization evidence','not executed or game-tested','not approved for publication')
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $taskOutput 'candidate.json') -Encoding utf8
    Get-Content -LiteralPath (Join-Path $taskOutput 'candidate.json') -Raw
} finally {
    foreach ($taskName in $taskEnvironmentNames) { [Environment]::SetEnvironmentVariable($taskName, $taskOldEnvironment[$taskName], 'Process') }
    Pop-Location
}
