param(
    [string]$ProjectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '../../build/hit-displacement-world'),
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path "$OutputRoot/classes" | Out-Null
$source=Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
& "$JdkHome/bin/javac.exe" --release 21 -d "$OutputRoot/classes" "$source/HitDisplacementAnimation.java" "$source/HitDisplacementWorld.java" "$PSScriptRoot/HitDisplacementWorldCheck.java"
if($LASTEXITCODE -ne 0){throw 'World hit displacement check compilation failed'}
& "$JdkHome/bin/java.exe" -ea -cp "$OutputRoot/classes" HitDisplacementWorldCheck
if($LASTEXITCODE -ne 0){throw 'World hit displacement checks failed'}
