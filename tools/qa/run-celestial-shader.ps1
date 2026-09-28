param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$BaselineJar='',
    [ValidateRange(192,1024)][int]$Width=576
)
$ErrorActionPreference='Stop';$PSNativeCommandUseErrorActionPreference=$false
$projectPath=(Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath=[IO.Path]::GetFullPath($OutputRoot);$classes=Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
if($BaselineJar){
    if(-not(Test-Path -LiteralPath $BaselineJar -PathType Leaf)){throw 'The explicitly supplied BaselineJar does not exist.'}
}else{
    $defaultBaseline=Join-Path $projectPath 'build\module-qa\obfuscation\celeste-plus-20260908-shader-fog-01\client-shader-fog.jar'
    if(Test-Path -LiteralPath $defaultBaseline -PathType Leaf){$BaselineJar=$defaultBaseline}
}
$cache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$libraries=@(rg --files (Join-Path $cache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($libraries.Count -ne 8){throw 'Expected eight cached LWJGL Windows jars; no download attempted.'}
$minecraft=Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
$classpath=$libraries -join ';';$qa=Join-Path $projectPath 'tools\qa';$helpers=Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
function Invoke-CelestialProcess {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1);$code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $outputPath "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 15 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-CelestialProcess (Join-Path $JdkHome 'bin\javac.exe') @('--release','21','-classpath',$classpath,'-d',$classes,
    (Join-Path $helpers 'AmbientField.java'),(Join-Path $helpers 'AmbientTesseractField.java'),(Join-Path $helpers 'CustomFogMath.java'),(Join-Path $qa 'ImpactCheck.java'),
    (Join-Path $qa 'AtmosphereCheck.java'),(Join-Path $qa 'CelestialShaderCheck.java')) 'compile'
$gpuArguments=@("-Dceleste.atmosphere.width=$Width",'-classpath',"$classes;$classpath",'CelestialShaderCheck',
    (Join-Path $projectPath 'src\main\resources'),$minecraft,$outputPath)
if($BaselineJar){$gpuArguments+=@($BaselineJar)}
$result=@(Invoke-CelestialProcess (Join-Path $JdkHome 'bin\java.exe') $gpuArguments 'gpu')
$result | Where-Object {"$_" -match '^PASS Celestial|^GPU_TIMER|^GPU preview|^GPU animation'} | Write-Output
