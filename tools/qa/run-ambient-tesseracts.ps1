param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$StagedRoot='',
    [string]$MinecraftJar='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot',
    [ValidateRange(192,1024)][int]$Width=768
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$particleProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
if(-not $StagedRoot){$StagedRoot=$particleProject}
$particleOutput=[IO.Path]::GetFullPath($OutputRoot);$particleClasses=Join-Path $particleOutput 'classes'
New-Item -ItemType Directory -Force -Path $particleClasses | Out-Null
$particleCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$particleLibraries=@(rg --files (Join-Path $particleCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($particleLibraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars.'}
if(-not $MinecraftJar){$MinecraftJar=Join-Path $particleProject '.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-b00516cda8/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'}
$particleClasspath=$particleLibraries -join ';';$particleQa=Join-Path $particleProject 'tools/qa';$particleHelpers=Join-Path $StagedRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
function Invoke-TesseractCheck {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1);$code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $particleOutput "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 12 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-TesseractCheck (Join-Path $JdkHome 'bin/javac.exe') @('--release','21','-classpath',$particleClasspath,'-d',$particleClasses,
    (Join-Path $particleHelpers 'AmbientField.java'),(Join-Path $particleHelpers 'AmbientTesseractField.java'),(Join-Path $particleHelpers 'AmbientTesseractGeometry.java'),
    (Join-Path $particleProject 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/CustomFogMath.java'),
    (Join-Path $particleQa 'ImpactCheck.java'),(Join-Path $particleQa 'AtmosphereCheck.java'),
    (Join-Path $PSScriptRoot 'AmbientTesseractCheck.java'),(Join-Path $PSScriptRoot 'AmbientTesseractGpuCheck.java')) 'compile'
Invoke-TesseractCheck (Join-Path $JdkHome 'bin/java.exe') @('-classpath',"$particleClasses;$particleClasspath",'AmbientTesseractCheck') 'behavior'
Invoke-TesseractCheck (Join-Path $JdkHome 'bin/java.exe') @("-Dceleste.atmosphere.width=$Width",'-classpath',"$particleClasses;$particleClasspath",'AmbientTesseractGpuCheck',
    (Join-Path $particleProject 'src/main/resources'),(Join-Path $StagedRoot 'src/main/resources'),$MinecraftJar,$particleOutput) 'gpu'
