param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [switch]$Gpu,
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { throw 'JDK 21 required; pass an existing -JdkHome.' }
$classes = Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
function Invoke-LeafQaProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$Log, [string]$Label)
    $result = @(& $Executable @Arguments 2>&1)
    $code = $LASTEXITCODE
    $result | Set-Content -LiteralPath (Join-Path $outputPath $Log) -Encoding utf8
    if ($code -ne 0) { throw "$Label failed (exit $code): $(($result | Select-Object -Last 8 | Out-String).Trim())" }
    $result | Where-Object { "$_" -match '^(PASS|GPU)' } | Write-Output
}
$packagePath = Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
Invoke-LeafQaProcess $javac @('--release', '21', '-d', $classes,
    (Join-Path $packagePath 'AmbientField.java'),(Join-Path $packagePath 'AmbientTesseractField.java'), (Join-Path $packagePath 'AmbientGeometry.java'),
    (Join-Path $PSScriptRoot 'FallingLeavesCheck.java')) 'compile.log' 'Leaf helper compilation'
Invoke-LeafQaProcess $java @('-classpath', $classes, 'FallingLeavesCheck') 'run.log' 'Leaf helper tests'
if (-not $Gpu) { return }
$lwjglPath = Join-Path $DependencyCache 'org.lwjgl'
if (Get-Command rg -ErrorAction SilentlyContinue) { $candidates = @(rg --files $lwjglPath -g '*.jar') }
else { $candidates = @(Get-ChildItem -LiteralPath $lwjglPath -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName }) }
$libraries = @($candidates | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
} | Sort-Object)
if ($libraries.Count -ne 8) { throw "Expected eight cached LWJGL 3.3.3 Windows jars; found $($libraries.Count). No downloads attempted." }
$minecraftJar = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
if (-not (Test-Path -LiteralPath $minecraftJar)) { throw 'Exact merged Minecraft 1.21.11 dependency cache missing.' }
$classpath = "$classes;$($libraries -join ';')"
Invoke-LeafQaProcess $javac @('--release', '21', '-classpath', $classpath, '-d', $classes,
    (Join-Path $packagePath 'CustomFogMath.java'), (Join-Path $PSScriptRoot 'ImpactCheck.java'),
    (Join-Path $PSScriptRoot 'AtmosphereCheck.java'), (Join-Path $PSScriptRoot 'FallingLeavesGpuCheck.java')) 'gpu-compile.log' 'Leaf GPU harness compilation'
Invoke-LeafQaProcess $java @('-Djava.awt.headless=true', '-Dceleste.atmosphere.width=576', '-classpath', $classpath,
    'FallingLeavesGpuCheck', (Join-Path $projectPath 'src\main\resources'), $minecraftJar, $outputPath) 'gpu-run.log' 'Leaf GPU checks'
