param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$ProductionClasses='',
    [string]$StagedRoot='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
if(-not $StagedRoot){$StagedRoot=$ProjectRoot}
if(-not $ProductionClasses){$ProductionClasses=Join-Path $ProjectRoot 'build/classes/java/main'}
$outputPath=[IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path "$outputPath/classes" | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
$libraries=@(rg --files $cache -g '*.jar' | Where-Object {
    $_ -match '[\\/](asm|asm-commons)[\\/]9\.9[\\/]' -and [IO.Path]::GetFileName($_) -match '^asm(?:-commons)?-9\.9\.jar$'
})
if($libraries.Count -ne 2){throw 'Expected cached ASM9.9 jars; no downloads attempted.'}
$classpath=$libraries -join ';'
& "$JdkHome/bin/javac.exe" --release 21 -cp $classpath -d "$outputPath/classes" "$StagedRoot/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementAnimation.java" "$StagedRoot/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementWorld.java" "$PSScriptRoot/HitDisplacementLifecycleCheck.java"
if($LASTEXITCODE -ne 0){throw 'Hit displacement lifecycle fixture compilation failed'}
& "$JdkHome/bin/java.exe" -cp "$outputPath/classes;$classpath" HitDisplacementLifecycleCheck $ProductionClasses
if($LASTEXITCODE -ne 0){throw 'Hit displacement lifecycle check failed'}
