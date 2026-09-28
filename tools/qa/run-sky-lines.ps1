param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [ValidateRange(192,1024)][int]$Width=576
)
$ErrorActionPreference='Stop';$PSNativeCommandUseErrorActionPreference=$false
$taskProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
$taskOutput=[IO.Path]::GetFullPath($OutputRoot);$taskClasses=Join-Path $taskOutput 'classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskCache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$taskLibraries=@(rg --files (Join-Path $taskCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($taskLibraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars; no download attempted.'}
$taskMinecraft=Join-Path $taskProject '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
$taskClasspath=$taskLibraries -join ';';$taskQa=Join-Path $taskProject 'tools\qa';$taskHelpers=Join-Path $taskProject 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
function Invoke-SkyLinesProcess {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1);$code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $taskOutput "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 12 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-SkyLinesProcess (Join-Path $JdkHome 'bin\javac.exe') @('--release','21','-classpath',$taskClasspath,'-d',$taskClasses,
    (Join-Path $taskHelpers 'AmbientField.java'),(Join-Path $taskHelpers 'AmbientTesseractField.java'),(Join-Path $taskHelpers 'CustomFogMath.java'),(Join-Path $taskQa 'ImpactCheck.java'),
    (Join-Path $taskQa 'AtmosphereCheck.java'),(Join-Path $taskHelpers 'SkyLinesMath.java'),(Join-Path $taskHelpers 'SkyLinesMesh.java'),
    (Join-Path $taskQa 'SkyLinesCheck.java'),(Join-Path $taskQa 'SkyLinesGpuCheck.java')) 'compile'
Invoke-SkyLinesProcess (Join-Path $JdkHome 'bin\java.exe') @('-classpath',"$taskClasses;$taskClasspath",'dev.c4lam.celeste.module.impl.visual.expansion.SkyLinesCheck') 'math'
Invoke-SkyLinesProcess (Join-Path $JdkHome 'bin\java.exe') @("-Dceleste.atmosphere.width=$Width",'-classpath',"$taskClasses;$taskClasspath",'SkyLinesGpuCheck',$taskMinecraft,$taskOutput,(Join-Path $taskProject 'src\main\resources')) 'gpu'
