param(
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$JnaJar,
    [Parameter(Mandatory=$true)][string]$JnaPlatformJar,
    [switch]$LocalParity
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$output = Join-Path $repository ('build\module-qa\hardware-fallback-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$classpath = (Resolve-Path -LiteralPath $JnaJar).Path + [IO.Path]::PathSeparator + (Resolve-Path -LiteralPath $JnaPlatformJar).Path
$sources = @(
    'runtime-overlay/src/main/java/dev/c4lam/celeste/auth/HardwareFingerprint.java',
    'runtime-overlay/src/main/java/dev/c4lam/celeste/auth/WindowsFirmwareIdentity.java',
    'build/runtime-core/src/main/java/dev/c4lam/celeste/auth/Crypto.java',
    'build/runtime-core/src/main/java/dev/c4lam/celeste/auth/PortableHardwareFingerprint.java',
    'beta-lab/shared-auth/src/main/java/dev/c4lam/celeste/auth/EnvironmentGate.java',
    'beta-lab/hardware-fallback-check/HardwareFingerprintFallbackCheck.java'
) | ForEach-Object { Join-Path $repository $_ }
& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -Xlint:all -Werror -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Hardware fallback compilation failed.' }
$arguments = @('-cp', ($output + [IO.Path]::PathSeparator + $classpath), 'dev.c4lam.celeste.auth.HardwareFingerprintFallbackCheck')
if ($LocalParity) { $arguments += '--local-parity' }
& (Join-Path $JdkHome 'bin/java.exe') @arguments
if ($LASTEXITCODE -ne 0) { throw 'Hardware fallback checks failed.' }
