param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$ShaderPath='',
    [string]$MinecraftJar='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot',
    [ValidateRange(192,1024)][int]$Width=768
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$skyProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
$skyOutput=[IO.Path]::GetFullPath($OutputRoot)
$skyClasses=Join-Path $skyOutput 'classes'
New-Item -ItemType Directory -Force -Path $skyClasses | Out-Null
$skyCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$skyLibraries=@(rg --files (Join-Path $skyCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($skyLibraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars; no download attempted.'}
if(-not $MinecraftJar){$MinecraftJar=Join-Path $skyProject '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-b00516cda8/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'}
if(-not $ShaderPath){$ShaderPath=Join-Path $skyProject 'src/main/resources/assets/celeste/shaders/core/skybox.fsh'}
$skyClasspath=$skyLibraries -join ';'
$skyQa=Join-Path $skyProject 'tools/qa'
$skyHelpers=Join-Path $skyProject 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
function Invoke-SkyCubeProcess {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1);$code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $skyOutput "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 12 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-SkyCubeProcess (Join-Path $JdkHome 'bin/javac.exe') @('--release','21','-classpath',$skyClasspath,'-d',$skyClasses,
    (Join-Path $skyHelpers 'AmbientField.java'),(Join-Path $skyHelpers 'CustomFogMath.java'),(Join-Path $skyQa 'ImpactCheck.java'),
    (Join-Path $skyQa 'AtmosphereCheck.java'),(Join-Path $PSScriptRoot 'SkyCubesCheck.java')) 'compile'
Invoke-SkyCubeProcess (Join-Path $JdkHome 'bin/java.exe') @("-Dceleste.atmosphere.width=$Width",'-classpath',"$skyClasses;$skyClasspath",'SkyCubesCheck',
    (Join-Path $skyProject 'src/main/resources'),$ShaderPath,$MinecraftJar,$skyOutput) 'gpu'
