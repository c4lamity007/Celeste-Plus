param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = '',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'),
    [ValidateSet('Math', 'Gpu', 'All')][string]$Suite = 'All',
    [ValidateRange(192, 1024)][int]$Width = 384
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [IO.Path]::GetFullPath($OutputRoot)
$candidates = if ($JdkHome) { @($JdkHome) } else {
    @($env:JAVA_HOME, (Join-Path $env:USERPROFILE '.cache\codex-jdks\temurin-21')) | Where-Object { $_ }
}
$resolvedJdk = $null
foreach ($candidate in $candidates) {
    $compiler = Join-Path $candidate 'bin\javac.exe'
    if (Test-Path -LiteralPath $compiler) {
        $version = (& $compiler -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac 21(?:\.|\s|$)') { $resolvedJdk = $candidate; break }
    }
}
if (-not $resolvedJdk) { throw 'Pass -JdkHome with an available Java 21 JDK.' }
$java = Join-Path $resolvedJdk 'bin\java.exe'
$javac = Join-Path $resolvedJdk 'bin\javac.exe'
$classes = Join-Path $outputPath 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
function Invoke-AtmosphereProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$Label)
    $lines = @(& $Executable @Arguments 2>&1)
    $code = $LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $outputPath "$Label.log") -Encoding utf8
    if ($code -ne 0) { throw "$Label failed (exit $code):`n$(($lines | Select-Object -Last 10 | Out-String).Trim())" }
    return $lines
}
$package = Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
$qa = Join-Path $projectPath 'tools\qa'
$helpers = @((Join-Path $package 'AmbientField.java'),(Join-Path $package 'AmbientTesseractField.java'), (Join-Path $package 'CustomFogMath.java'), (Join-Path $package 'SkyboxMath.java'))
if ($Suite -in @('Math', 'All')) {
    $null = Invoke-AtmosphereProcess $javac (@('--release', '21', '-d', $classes) + $helpers + @((Join-Path $qa 'AtmosphereRulesCheck.java'))) 'math-compile'
    $result = @(Invoke-AtmosphereProcess $java @('-classpath', $classes, 'AtmosphereRulesCheck') 'math-run')
    $result | Where-Object { "$_" -match '^PASS ' } | Write-Output
}
if ($Suite -in @('Gpu', 'All')) {
    $libraries = @(rg --files (Join-Path $DependencyCache 'org.lwjgl') -g '*.jar' | Where-Object {
        $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
        [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
    })
    if ($libraries.Count -ne 8) { throw 'Expected eight cached LWJGL 3.3.3 Windows jars; no downloads are attempted.' }
    $minecraft = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
    if (-not (Test-Path -LiteralPath $minecraft)) { throw 'Selected project must have its exact cached Minecraft 1.21.11 merged jar.' }
    $classpath = $libraries -join ';'
    $null = Invoke-AtmosphereProcess $javac @('--release', '21', '-classpath', $classpath, '-d', $classes,
        (Join-Path $package 'AmbientField.java'),(Join-Path $package 'AmbientTesseractField.java'), (Join-Path $package 'CustomFogMath.java'), (Join-Path $qa 'ImpactCheck.java'), (Join-Path $qa 'AtmosphereCheck.java')) 'gpu-compile'
    $result = @(Invoke-AtmosphereProcess $java @("-Dceleste.atmosphere.width=$Width", '-classpath', "$classes;$classpath", 'AtmosphereCheck',
        (Join-Path $projectPath 'src\main\resources'), $minecraft, $outputPath) 'gpu-run')
    $result | Where-Object { "$_" -match '^PASS Atmosphere GPU:|^GPU preview:' } | Write-Output
}
