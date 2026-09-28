param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = '',
    [string]$DependencyCache = (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'),
    [ValidateSet('Animation', 'Gpu', 'All')][string]$Suite = 'Animation',
    [ValidateRange(32, 2048)][int]$Tile = 320
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputRoot)

function Resolve-QaJdk21 {
    param([string]$Requested)
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($Requested) {
        $candidates.Add($Requested)
    } else {
        if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
        $candidates.Add((Join-Path $env:USERPROFILE '.cache\codex-jdks\temurin-21'))
        $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javacCommand) { $candidates.Add((Split-Path -Parent (Split-Path -Parent $javacCommand.Source))) }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        $javac = Join-Path $candidate 'bin\javac.exe'
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) { continue }
        $version = (& $javac -version 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac 21(?:\.|\s|$)') {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'JDK 21 was not found. Pass -JdkHome with a Java 21 installation containing bin\java.exe and bin\javac.exe.'
}

function Invoke-QaProcess {
    param([string]$Executable, [string[]]$Arguments, [string]$LogPath, [string]$Label)
    $processOutput = @(& $Executable @Arguments 2>&1)
    $processExit = $LASTEXITCODE
    if ($processOutput.Count -eq 0) {
        "[No process output; exit $processExit]" | Set-Content -LiteralPath $LogPath -Encoding utf8
    } else {
        $processOutput | Set-Content -LiteralPath $LogPath -Encoding utf8
    }
    if ($processExit -ne 0) {
        $tail = ($processOutput | Select-Object -Last 8 | Out-String).Trim()
        throw "$Label failed (exit $processExit). Log: $LogPath`n$tail"
    }
    return $processOutput
}

$resolvedJdk = Resolve-QaJdk21 -Requested $JdkHome
$javaPath = Join-Path $resolvedJdk 'bin\java.exe'
$javacPath = Join-Path $resolvedJdk 'bin\javac.exe'
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null

if ($Suite -in @('Animation', 'All')) {
    $animationOutput = Join-Path $outputPath 'animation'
    $animationClasses = Join-Path $animationOutput 'classes'
    New-Item -ItemType Directory -Force -Path $animationClasses | Out-Null
    $package = Join-Path $projectPath 'src\main\java\dev\c4lam\celeste\module\impl\visual\expansion'
    $helper = Join-Path $package 'JumpCircleAnimation.java'
    $mesh = Join-Path $package 'WaterJumpMesh.java'
    $solidMesh = Join-Path $package 'SolidVisualMesh.java'
    $surfaceMesh = Join-Path $package 'VolumeSurfaceMesh.java'
    $targetShape = Join-Path $package 'TargetAuraShape.java'
    $targetOrbit = Join-Path $package 'TargetOrbitMesh.java'
    $feature = Join-Path $package 'JumpCirclesFeature.java'
    foreach ($source in @($helper, $mesh, $solidMesh, $surfaceMesh, $targetShape, $targetOrbit, $feature)) {
        if (-not (Test-Path -LiteralPath $source)) { throw "Required project source missing: $source" }
    }
    $null = Invoke-QaProcess -Executable $javacPath -Arguments @(
        '--release', '21', '-d', $animationClasses, $helper, $mesh, $solidMesh, $surfaceMesh, $targetShape, $targetOrbit,
        (Join-Path $PSScriptRoot 'JumpCircleAnimationCheck.java'), (Join-Path $PSScriptRoot 'WaterJumpMeshCheck.java'),
        (Join-Path $PSScriptRoot 'SolidVisualMeshCheck.java'), (Join-Path $PSScriptRoot 'VolumeSurfaceMeshCheck.java'),
        (Join-Path $PSScriptRoot 'TargetAuraShapeCheck.java'), (Join-Path $PSScriptRoot 'TargetOrbitMeshCheck.java')
    ) -LogPath (Join-Path $animationOutput 'compile.log') -Label 'Animation compilation'
    $animationLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.JumpCircleAnimationCheck', $feature
    ) -LogPath (Join-Path $animationOutput 'run.log') -Label 'Animation checks')
    $summary = ($animationLog | Where-Object { "$_" -match '^PASS \d+ deterministic assertions' } | Select-Object -Last 1)
    Write-Output "Animation: $summary"
    $meshLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.WaterJumpMeshCheck'
    ) -LogPath (Join-Path $animationOutput 'water-mesh.log') -Label 'Water mesh checks')
    $meshSummary = ($meshLog | Where-Object { "$_" -match '^PASS WaterJumpMesh:' } | Select-Object -Last 1)
    Write-Output "Water mesh: $meshSummary"
    $solidLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.SolidVisualMeshCheck'
    ) -LogPath (Join-Path $animationOutput 'solid-mesh.log') -Label 'Solid mesh checks')
    Write-Output "Solid mesh: $(($solidLog | Select-Object -Last 1))"
    $surfaceLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.VolumeSurfaceMeshCheck'
    ) -LogPath (Join-Path $animationOutput 'volume-surface.log') -Label 'Surface seam checks')
    Write-Output "Surface seams: $(($surfaceLog | Select-Object -Last 1))"
    $targetLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.TargetAuraShapeCheck'
    ) -LogPath (Join-Path $animationOutput 'target-shape.log') -Label 'Body-fit target checks')
    Write-Output "Target shape: $(($targetLog | Select-Object -Last 1))"
    $orbitLog = @(Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-classpath', $animationClasses, 'dev.c4lam.celeste.module.impl.visual.expansion.TargetOrbitMeshCheck'
    ) -LogPath (Join-Path $animationOutput 'target-orbit.log') -Label 'Compact target orbit checks')
    Write-Output "Target orbit: $(($orbitLog | Select-Object -Last 1))"
}

if ($Suite -in @('Gpu', 'All')) {
    $gpuOutput = Join-Path $outputPath 'gpu'
    $gpuClasses = Join-Path $gpuOutput 'classes'
    $gpuPreview = Join-Path $gpuOutput 'preview'
    New-Item -ItemType Directory -Force -Path $gpuClasses, $gpuPreview | Out-Null
    $resources = Join-Path $projectPath 'src\main\resources'
    $minecraftJar = Join-Path $projectPath '.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-b00516cda8\1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2\minecraft-merged-b00516cda8-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar'
    if (-not (Test-Path -LiteralPath $minecraftJar)) {
        throw 'The selected project is missing its exact merged Minecraft 1.21.11 cache. Run its normal dependency setup first; this runner does not download dependencies or use another project cache.'
    }
    $lwjglCache = Join-Path $DependencyCache 'org.lwjgl'
    if (-not (Test-Path -LiteralPath $lwjglCache)) { throw "LWJGL dependency cache missing: $lwjglCache" }
    if (Get-Command rg -ErrorAction SilentlyContinue) {
        $jarCandidates = @(rg --files $lwjglCache -g '*.jar')
    } else {
        $jarCandidates = @(Get-ChildItem -LiteralPath $lwjglCache -Recurse -File -Filter '*.jar' | ForEach-Object { $_.FullName })
    }
    $libraries = @($jarCandidates | Where-Object {
        $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
        [System.IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
    } | Sort-Object)
    if ($libraries.Count -ne 8) { throw "Expected eight LWJGL 3.3.3 Java/Windows-native jars, found $($libraries.Count). No dependency download is attempted." }
    $classpath = $libraries -join ';'
    $null = Invoke-QaProcess -Executable $javacPath -Arguments @(
        '--release', '21', '-classpath', $classpath, '-d', $gpuClasses, (Join-Path $PSScriptRoot 'ImpactCheck.java')
    ) -LogPath (Join-Path $gpuOutput 'compile.log') -Label 'GPU harness compilation'
    $null = Invoke-QaProcess -Executable $javaPath -Arguments @(
        '-Djava.awt.headless=true', "-Dceleste.test.tile=$Tile", '-classpath', "$gpuClasses;$classpath",
        'ImpactCheck', $resources, $minecraftJar, $gpuPreview, (Join-Path $PSScriptRoot 'textures.properties')
    ) -LogPath (Join-Path $gpuOutput 'run.log') -Label 'GPU checks'
    $previewName = if ($Tile -eq 320) { 'gpu-contact-sheet.png' } else { "gpu-contact-sheet-$Tile.png" }
    $previewPath = Join-Path $gpuPreview $previewName
    @(
        'GPU PREVIEW - direct offscreen shader render; NOT an in-game screenshot.'
        'Columns: Ribbon | Pulse | Jump Glow | Jump Lean | Splash | Droplet | Water Wave 3D | Solid Volume'
        'Rows: GameTime 3.000000 s | 3.016667 s | 3.500000 s'
        'The first six materials use additive blending on a neutral background. Assertions inspect unblended floating-point output.'
        'Water uses translucent blending; its flat column is a surface-sampling fixture. The posed 3D water geometry preview is separate.'
        'Volume uses translucent blending and filled quad boundaries. Its full 3D mesh preview is separate.'
        'This does not test Minecraft runtime integration, world placement, or actual gameplay.'
    ) | Set-Content -LiteralPath (Join-Path $gpuPreview 'GPU-PREVIEW-README.txt') -Encoding utf8
    Write-Output "Gpu: PASS eight actual materials at ${Tile}x${Tile}; GPU preview: $previewPath (not an in-game screenshot)."
}

Write-Output "QA complete: suite=$Suite; logs/artifacts=$outputPath"
