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
$tronClasses=Join-Path $OutputRoot 'classes'
New-Item -ItemType Directory -Force -Path $tronClasses | Out-Null
$tronHelpers=Join-Path $StagedRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
$tronBase=Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
function Tron-Source([string]$name) { $path=Join-Path $tronHelpers $name; if(Test-Path -LiteralPath $path){return $path}; return (Join-Path $tronBase $name) }
$tronCompile=@(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -d $tronClasses (Tron-Source 'AmbientTronField.java') (Tron-Source 'AmbientField.java') (Tron-Source 'AmbientTesseractField.java') (Tron-Source 'AmbientTronGeometry.java') (Join-Path $tronBase 'JumpCircleAnimation.java') (Join-Path $PSScriptRoot 'AmbientTronCheck.java') 2>&1)
$tronExit=$LASTEXITCODE
$tronCompile | Set-Content -LiteralPath (Join-Path $OutputRoot 'compile.log') -Encoding utf8
if($tronExit -ne 0){throw "Tron compile failed: $($tronCompile | Select-Object -Last 12 | Out-String)"}
$tronRun=@(& (Join-Path $JdkHome 'bin/java.exe') -cp $tronClasses dev.c4lam.celeste.module.impl.visual.expansion.AmbientTronCheck 2>&1)
$tronExit=$LASTEXITCODE
$tronRun | Set-Content -LiteralPath (Join-Path $OutputRoot 'behavior.log') -Encoding utf8
if($tronExit -ne 0){throw "Tron behavior failed: $($tronRun | Select-Object -Last 12 | Out-String)"}
$tronRun
$tronCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$tronLibraries=@(rg --files (Join-Path $tronCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($tronLibraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars.'}
$tronClasspath=$tronLibraries -join ';'
$tronField=Join-Path $tronHelpers 'AmbientField.java'
if(-not (Test-Path -LiteralPath $tronField)){$tronField=Join-Path $tronBase 'AmbientField.java'}
if(-not $MinecraftJar){$MinecraftJar=Join-Path $ProjectRoot '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-b00516cda8/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'}
$tronCompile=@(& (Join-Path $JdkHome 'bin/javac.exe') --release 21 -cp "$tronClasses;$tronClasspath" -d $tronClasses (Join-Path $tronBase 'CustomFogMath.java') $tronField (Tron-Source 'AmbientTesseractField.java') (Join-Path $ProjectRoot 'tools/qa/ImpactCheck.java') (Join-Path $ProjectRoot 'tools/qa/AtmosphereCheck.java') (Join-Path $PSScriptRoot 'AmbientTronGpuCheck.java') 2>&1)
$tronExit=$LASTEXITCODE
$tronCompile | Set-Content -LiteralPath (Join-Path $OutputRoot 'gpu-compile.log') -Encoding utf8
if($tronExit -ne 0){throw "Tron GPU compile failed: $($tronCompile | Select-Object -Last 12 | Out-String)"}
$tronRun=@(& (Join-Path $JdkHome 'bin/java.exe') -cp "$tronClasses;$tronClasspath" AmbientTronGpuCheck (Join-Path $ProjectRoot 'src/main/resources') $MinecraftJar $OutputRoot 2>&1)
$tronExit=$LASTEXITCODE
$tronRun | Set-Content -LiteralPath (Join-Path $OutputRoot 'gpu.log') -Encoding utf8
if($tronExit -ne 0){throw "Tron GPU check failed: $($tronRun | Select-Object -Last 12 | Out-String)"}
$tronRun
