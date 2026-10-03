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
# Keep the real selected signature/context verifier while substituting only transport.
$fixtureService = Join-Path $testRoot 'src\dev\c4lam\celeste\auth\AuthServiceClient.java'
$serviceText = Get-Content -LiteralPath (Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\AuthServiceClient.java') -Raw
$methodStart = $serviceText.IndexOf('    static Decision verifyIncidentDecision(')
$methodEnd = $serviceText.IndexOf('    /** Punitive reporting is absent', $methodStart)
if ($methodStart -lt 0 -or $methodEnd -le $methodStart) { throw 'Actual incident verifier boundary changed; review fixture extraction.' }
$verifier = $serviceText.Substring($methodStart, $methodEnd - $methodStart)
$stubText = Get-Content -LiteralPath $fixtureService -Raw
$stubText = $stubText.Replace('final class AuthServiceClient implements AutoCloseable {', 'final class AuthServiceClient implements AutoCloseable {' + [Environment]::NewLine + $verifier)
$generatedService = Join-Path $classesDirectory 'AuthServiceClient.java'
[System.IO.File]::WriteAllText($generatedService, $stubText, [System.Text.UTF8Encoding]::new($false))
$sources = @($sources | Where-Object { $_ -ne $fixtureService }) + $generatedService
$sources += Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\AuthBootstrap.java'
$sources += Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\SecurityIncidentController.java'
$sources += Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\SecurityIncidentProtocol.java'
$sources += Join-Path $repositoryRoot 'runtime-overlay\src\main\java\dev\c4lam\celeste\auth\SecurityWarningRenderer.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\c4lam\celeste\auth\Crypto.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\c4lam\celeste\license\LicenseIdentity.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\c4lam\celeste\beta\lifecycle\BetaSubscriptions.java'
$sources += Join-Path $repositoryRoot 'build\runtime-core\src\main\java\dev\celestebeta\host\DetachableCallbacks.java'
$gson = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.13.2') -Filter 'gson-2.13.2.jar' -Recurse | Select-Object -First 1 -ExpandProperty FullName
if (-not $gson) { throw 'Pinned Gson 2.13.2 dependency is unavailable; run the normal Gradle dependency preparation.' }
$testClasspath = $classesDirectory + ';' + $gson
& $javacCommand --release 21 -Xlint:all -Werror -cp $gson -d $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Auth lifecycle compilation failed.' }

$scenarios = @(
    'credential-cleanup-failure', 'credential-cleanup-queued-stop', 'credential-cleanup-active-stop', 'credential-cleanup-revocation',
    'credential-queued-replacement',
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
    'heartbeat-recovery-stop-race', 'heartbeat-recovery-expired', 'heartbeat-recovery-waits-for-termination',
    'supervisor-module-expiry-failure'
)
if (-not $SkipTimedHeartbeat) { $scenarios += 'heartbeat-scheduled' }
foreach ($scenario in $scenarios) {
    & $javaCommand -Xverify:all -ea -cp $testClasspath dev.c4lam.celeste.auth.AuthBootstrapLifecycleTest $scenario
    if ($LASTEXITCODE -ne 0) { throw "Auth lifecycle scenario failed: $scenario" }
}
Write-Output "PASS: $($scenarios.Count) auth lifecycle scenarios against production AuthBootstrap.java."
foreach ($scenario in @('warning-stop','scope-unset','warning-retirement','warning-cleared','warning-untrusted','warning-starved','quarantine-start-expiry','plus-startup-mismatch','warning-renderer','debugger-classification')) {
    & $javaCommand -Xverify:all -ea -cp $testClasspath dev.c4lam.celeste.auth.AuthBootstrapIncidentLifecycleCheck $scenario
    if ($LASTEXITCODE -ne 0) { throw "Incident lifecycle scenario failed: $scenario" }
}
Write-Output 'PASS: 10 incident host integration scenarios; synthetic transport/probe/game actions only.'
