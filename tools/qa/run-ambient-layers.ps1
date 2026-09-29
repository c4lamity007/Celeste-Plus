param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$StagedRoot='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot',
    [string]$MinecraftJar=''
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
if(-not $StagedRoot){$StagedRoot=$ProjectRoot}
$layersClasses=Join-Path $OutputRoot 'classes'
New-Item -ItemType Directory -Force -Path $layersClasses | Out-Null
$layersPackage='src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
function Get-LayerSource([string]$Name) {
    $candidate=Join-Path $StagedRoot "$layersPackage/$Name.java"
    if(Test-Path -LiteralPath $candidate){return $candidate}
    return Join-Path $ProjectRoot "$layersPackage/$Name.java"
}
$layersSources=@('AmbientField','AmbientTesseractField','AmbientTesseractGeometry','AmbientTronField','AmbientTronGeometry','AmbientGeometry','JumpCircleAnimation','AmbientEffectsLayers','AmbientEffectsAirCache','AmbientEffectsGeometry','VisualAtmosphere') | ForEach-Object {Get-LayerSource $_}
$layersCompile=@(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -d $layersClasses $layersSources (Join-Path $PSScriptRoot 'AmbientLayersCheck.java') (Join-Path $PSScriptRoot 'AmbientDensityCheck.java') 2>&1)
$layersExit=$LASTEXITCODE
$layersCompile | Set-Content -LiteralPath (Join-Path $OutputRoot 'compile.log') -Encoding utf8
if($layersExit -ne 0){throw "Layers compile failed: $($layersCompile | Select-Object -Last 12 | Out-String)"}
$layersRun=@(& (Join-Path $JdkHome 'bin/java.exe') -cp $layersClasses dev.c4lam.celeste.module.impl.visual.expansion.AmbientLayersCheck 2>&1)
$layersExit=$LASTEXITCODE
$layersRun | Set-Content -LiteralPath (Join-Path $OutputRoot 'behavior.log') -Encoding utf8
if($layersExit -ne 0){throw "Layers behavior failed: $($layersRun | Select-Object -Last 12 | Out-String)"}
$layersRun
$layersDensity=@(& (Join-Path $JdkHome 'bin/java.exe') -cp $layersClasses dev.c4lam.celeste.module.impl.visual.expansion.AmbientDensityCheck 2>&1)
$layersExit=$LASTEXITCODE
$layersDensity | Set-Content -LiteralPath (Join-Path $OutputRoot 'density-color.log') -Encoding utf8
if($layersExit -ne 0){throw "Density/color failed: $($layersDensity | Select-Object -Last 12 | Out-String)"}
$layersDensity
$layersCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$layersLibraries=@(rg --files (Join-Path $layersCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($layersLibraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars.'}
$layersClasspath=$layersLibraries -join ';'
if(-not $MinecraftJar){$MinecraftJar=Join-Path $ProjectRoot '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-b00516cda8/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'}
$layersCompile=@(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -cp "$layersClasses;$layersClasspath" -d $layersClasses (Get-LayerSource 'CustomFogMath') (Join-Path $ProjectRoot 'tools/qa/ImpactCheck.java') (Join-Path $ProjectRoot 'tools/qa/AtmosphereCheck.java') (Join-Path $ProjectRoot 'tools/qa/AmbientLayersGpuCheck.java') 2>&1)
$layersExit=$LASTEXITCODE
$layersCompile | Set-Content -LiteralPath (Join-Path $OutputRoot 'gpu-compile.log') -Encoding utf8
if($layersExit -ne 0){throw "Layers GPU compile failed: $($layersCompile | Select-Object -Last 12 | Out-String)"}
$layersRun=@(& (Join-Path $JdkHome 'bin/java.exe') -cp "$layersClasses;$layersClasspath" AmbientLayersGpuCheck (Join-Path $ProjectRoot 'src/main/resources') $MinecraftJar $OutputRoot 2>&1)
$layersExit=$LASTEXITCODE
$layersRun | Set-Content -LiteralPath (Join-Path $OutputRoot 'gpu.log') -Encoding utf8
if($layersExit -ne 0){throw "Layers GPU failed: $($layersRun | Select-Object -Last 12 | Out-String)"}
$layersRun
