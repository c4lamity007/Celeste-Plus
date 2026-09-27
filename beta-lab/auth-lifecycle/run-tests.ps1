param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipTimedHeartbeat
)

$ErrorActionPreference = 'Stop'
$testRoot = $PSScriptRoot
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $testRoot '..\..')).Path
$classesDirectory = Join-Path $repositoryRoot ('build\module-qa\auth-lifecycle\test-classes-' + [Guid]::NewGuid().ToString('N'))
$javacCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\javac.exe' } else { 'javac' }
$javaCommand = if ($JdkHome) { Join-Path $JdkHome 'bin\java.exe' } else { 'java' }

& $javaCommand -version
if ($LASTEXITCODE -ne 0) { throw 'Java is unavailable.' }
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $testRoot 'src') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName })
$sources += Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\AuthBootstrap.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\c4lam\celeste\license\LicenseIdentity.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\c4lam\celeste\beta\lifecycle\BetaSubscriptions.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\celestebeta\host\DetachableCallbacks.java'
& $javacCommand --release 21 -Xlint:all -Werror -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Auth lifecycle compilation failed.' }

$scenarios = @(
    'startup-deferred', 'late-start-once', 'late-failure-observer', 'late-scoped-stop', 'duplicate-owner',
    'initializer-runtime-failure', 'initializer-linkage-failure',
    'initializer-stops', 'initializer-revokes',
    'initializer-stops-off-thread', 'initializer-revokes-off-thread',
    'queued-before-stop', 'revoked-cannot-reopen', 'startup-integrity',
    'beta-context-pinned', 'beta-context-needs-memory', 'beta-context-too-late',
    'plus-reactivation-preserve', 'standard-reactivation-preserve', 'plus-concurrency-preserve', 'plus-token-revoked-preserve',
    'heartbeat-refresh', 'heartbeat-reject-401', 'heartbeat-reject-403',
    'heartbeat-reject-409', 'heartbeat-grace', 'heartbeat-integrity',
    'heartbeat-response-after-stop', 'heartbeat-security-rejection',
    'startup-needs-online-proof', 'queued-session-expired', 'expiry-without-worker', 'late-response-after-expiry',
    'heartbeat-worker-recovery', 'heartbeat-task-recovery', 'heartbeat-recovery-exhausted',
    'heartbeat-recovery-stop-race', 'heartbeat-recovery-expired', 'heartbeat-recovery-waits-for-termination'
)
if (-not $SkipTimedHeartbeat) { $scenarios += 'heartbeat-scheduled' }
foreach ($scenario in $scenarios) {
    & $javaCommand -ea -cp $classesDirectory dev.c4lam.celeste.auth.AuthBootstrapLifecycleTest $scenario
    if ($LASTEXITCODE -ne 0) { throw "Auth lifecycle scenario failed: $scenario" }
}
Write-Output "PASS: $($scenarios.Count) auth lifecycle scenarios against production AuthBootstrap.java."
