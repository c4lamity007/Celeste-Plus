param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [ValidateRange(192,1024)][int]$Width=384
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$projectPath=(Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath=[IO.Path]::GetFullPath($OutputRoot)
$classes=Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$libraries=@(rg --files (Join-Path $cache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if($libraries.Count -ne 8){throw 'Expected eight cached LWJGL 3.3.3 Windows jars; no downloads attempted.'}
$asm=@(rg --files (Join-Path $cache 'org.ow2.asm') -g '*.jar' | Where-Object {
    $_ -match '[\\/](asm|asm-commons)[\\/]9\.9[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^asm(?:-commons)?-9\.9\.jar$'
})
if($asm.Count -ne 2){throw 'Expected two cached ASM9.9 fixture jars.'}
$minecraft=Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if(-not(Test-Path -LiteralPath $minecraft)){throw 'The exact Minecraft 1.21.11 merged cache is required.'}
$classpath=(@($libraries)+@($asm)) -join ';'
$qa=Join-Path $projectPath 'tools\qa'
$helpers=Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
function Invoke-ShaderFogProcess {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1)
    $code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $outputPath "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 12 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-ShaderFogProcess (Join-Path $JdkHome 'bin\javac.exe') @('--release','21','-classpath',$classpath,'-d',$classes,
    (Join-Path $helpers 'AmbientField.java'),(Join-Path $helpers 'AmbientTesseractField.java'),(Join-Path $helpers 'CustomFogMath.java'),
    (Join-Path $helpers 'VisualAtmosphere.java'),(Join-Path $helpers 'SkyboxMath.java'),(Join-Path $helpers 'ShaderFogMath.java'),(Join-Path $qa 'ShaderFogRulesCheck.java'),
    (Join-Path $qa 'ShaderFogLifecycleCheck.java'),
    (Join-Path $qa 'ImpactCheck.java'),(Join-Path $qa 'AtmosphereCheck.java'),(Join-Path $qa 'ShaderFogCheck.java')) 'compile'
$rules=@(Invoke-ShaderFogProcess (Join-Path $JdkHome 'bin\java.exe') @('-classpath',$classes,'ShaderFogRulesCheck') 'rules')
$rules | Where-Object { "$_" -match '^PASS ShaderFogRules:' } | Write-Output
$lifecycle=@(Invoke-ShaderFogProcess (Join-Path $JdkHome 'bin\java.exe') @('-classpath',"$classes;$classpath",'ShaderFogLifecycleCheck',
    (Join-Path $projectPath 'build\classes\java\main')) 'lifecycle')
$lifecycle | Where-Object { "$_" -match '^PASS ShaderFogLifecycle:' } | Write-Output
$result=@(Invoke-ShaderFogProcess (Join-Path $JdkHome 'bin\java.exe') @("-Dceleste.atmosphere.width=$Width",'-classpath',"$classes;$classpath",'ShaderFogCheck',
    (Join-Path $projectPath 'src\main\resources'),$minecraft,$outputPath) 'gpu')
$result | Where-Object { "$_" -match '^PASS ShaderFog|^GPU preview:' } | Write-Output
