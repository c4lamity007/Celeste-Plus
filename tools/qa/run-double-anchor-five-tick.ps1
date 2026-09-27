param(
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$RuntimeRoot = (Join-Path $PSScriptRoot '..\..\build\runtime-core'),
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\..\build\module-qa\double-anchor-five-tick'),
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$taskRuntime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$taskOutput = [IO.Path]::GetFullPath($OutputRoot)
$taskClasses = Join-Path $taskOutput ('classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskClasses | Out-Null
$taskSources = @('action/ActionScheduler.java', 'action/PrimaryAction.java', 'silent/SilentSlotManager.java') |
    ForEach-Object { Join-Path $taskRuntime "src/main/java/dev/c4lam/celeste/$_" }
$taskSources += Join-Path $taskRoot 'runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/combat/CrystalCombatCoordinator.java'
$taskSources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'double-anchor-five-tick-stubs') -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })
$taskSources += Join-Path $PSScriptRoot 'DoubleAnchorFiveTickCheck.java'
$taskCompile = @(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -proc:none -encoding UTF-8 -Xlint:all -Werror -d $taskClasses @taskSources 2>&1)
$taskExit = $LASTEXITCODE
$taskCompile | Set-Content -LiteralPath (Join-Path $taskOutput 'compile.log')
if ($taskExit -ne 0) { throw "Double Anchor fixture compilation failed: $($taskCompile | Select-Object -Last 18)" }
$taskResult = @(& (Join-Path $JdkHome 'bin/java.exe') -ea -cp $taskClasses dev.c4lam.celeste.module.impl.combat.DoubleAnchorFiveTickCheck 2>&1)
$taskExit = $LASTEXITCODE
$taskResult | Set-Content -LiteralPath (Join-Path $taskOutput 'behavior.log')
if ($taskExit -ne 0) { throw "Double Anchor behavior failed: $($taskResult | Select-Object -Last 12)" }
$taskResult | Write-Output
Write-Output 'Uses the real overlay coordinator, scheduler and slot manager; Minecraft prediction/server behavior is a bounded fixture. In-game acceptance remains unverified.'
