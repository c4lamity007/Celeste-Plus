param(
    [string]$ProjectRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$OutputRoot=(Join-Path $PSScriptRoot '../../build/hit-displacement-world'),
    [string]$StagedRoot='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path "$OutputRoot/classes" | Out-Null
$source=Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
$changed=if($StagedRoot){Join-Path $StagedRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'}else{$source}
$joml=@(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8') -g '*.jar')
if($joml.Count -ne 1){throw 'Expected pinned JOML 1.10.8 jar'}
& "$JdkHome/bin/javac.exe" --release 21 -cp $joml[0] -d "$OutputRoot/classes" "$source/HitDisplacementAnimation.java" "$changed/HitDisplacementWorld.java" "$changed/HitDisplacementProjection.java" "$PSScriptRoot/HitDisplacementWorldCheck.java"
if($LASTEXITCODE -ne 0){throw 'World hit displacement check compilation failed'}
& "$JdkHome/bin/java.exe" -ea -cp "$OutputRoot/classes;$($joml[0])" HitDisplacementWorldCheck
if($LASTEXITCODE -ne 0){throw 'World hit displacement checks failed'}
