param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [string]$JomlJar = ''
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not $JomlJar) {
    $cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8'
    $found=@(Get-ChildItem -LiteralPath $cache -Recurse -File -Filter 'joml-1.10.8.jar')
    if($found.Count -ne 1){throw 'Pass -JomlJar with the project JOML 1.10.8 dependency.'}
    $JomlJar=$found[0].FullName
}
$out=[IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path "$out/classes" | Out-Null
$pkg=Join-Path $project 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
$sources=@('JumpCircleAnimation','SolidVisualMesh','TargetOrbitMesh','HandModelsPose','HandModelsLayout','SwingAnimationPose') | ForEach-Object {Join-Path $pkg "$_.java"}
$checks=@('JumpCircleAnimationCheck','TargetOrbitMeshCheck','SwingAnimationPoseCheck')
$testSources=$checks | ForEach-Object {Join-Path $PSScriptRoot "$_.java"}
& "$JdkHome/bin/javac.exe" --release 21 -classpath $JomlJar -d "$out/classes" @sources @testSources *> "$out/compile.log"
if($LASTEXITCODE -ne 0){Get-Content "$out/compile.log" -Tail 15;throw 'Visual helper compilation failed'}
foreach($check in $checks) {
    & "$JdkHome/bin/java.exe" -classpath "$out/classes;$JomlJar" "dev.c4lam.celeste.module.impl.visual.expansion.$check" *> "$out/$check.log"
    if($LASTEXITCODE -ne 0){Get-Content "$out/$check.log" -Tail 15;throw "$check failed"}
    Get-Content "$out/$check.log" -Tail 1
}
Write-Output 'Focused visual logic passed. Shader driver and identified game build remain separate checks.'
