param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$MinecraftJar,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$StagedRoot='',
    [string]$ReferenceShaders='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
if(-not $StagedRoot){$StagedRoot=$ProjectRoot}
$sourcePath=(Resolve-Path -LiteralPath $StagedRoot).Path
$gamePath=(Resolve-Path -LiteralPath $MinecraftJar).Path
$outputPath=[IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path "$outputPath/classes" | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl'
$libraries=@(rg --files $cache -g '*.jar' | Where-Object {
    $_ -match '[\\/]3\.3\.3[\\/]' -and [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$'
})
if($libraries.Count -ne 6){throw 'Expected six cached LWJGL 3.3.3 Java/Windows jars; no downloads attempted.'}
$joml=@(rg --files (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.joml/joml/1.10.8') -g '*.jar')
if($joml.Count -ne 1){throw 'Expected pinned JOML 1.10.8 jar'}
$classpath=($libraries+$joml) -join ';'
$animation=Join-Path $sourcePath 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementAnimation.java'
if(-not(Test-Path -LiteralPath $animation)){$animation=Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementAnimation.java'}
& "$JdkHome/bin/javac.exe" --release 21 -cp $classpath -d "$outputPath/classes" $animation "$sourcePath/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementWorld.java" "$sourcePath/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HitDisplacementProjection.java" "$PSScriptRoot/HitDisplacementCheck.java"
if($LASTEXITCODE -ne 0){throw 'Hit displacement fixture compilation failed'}
$shaders=Join-Path $sourcePath 'src/main/resources/assets/celeste/shaders/core'
if(-not(Test-Path -LiteralPath "$shaders/hit_displacement.fsh")){$shaders=Join-Path $ProjectRoot 'src/main/resources/assets/celeste/shaders/core'}
$referenceArguments=@()
if($ReferenceShaders){$referenceArguments=@((Resolve-Path -LiteralPath $ReferenceShaders).Path)}
& "$JdkHome/bin/java.exe" -cp "$outputPath/classes;$classpath" HitDisplacementCheck $shaders $gamePath $outputPath @referenceArguments
if($LASTEXITCODE -ne 0){throw 'Hit displacement GPU check failed'}
