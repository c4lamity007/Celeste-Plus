param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$MinecraftJar,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$ShaderRoot='',
    [string]$SourceRoot='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
if (!$ShaderRoot) { $ShaderRoot=Join-Path $ProjectRoot 'src/main/resources/assets/celeste/shaders/core' }
New-Item -ItemType Directory -Force -Path "$OutputRoot/classes" | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl'
$libraries=@(rg --files $cache -g '*.jar' | Where-Object {
    $_ -match '[\\/]3\.3\.3[\\/]' -and [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$'
})
if($libraries.Count -ne 6){throw 'Expected six cached LWJGL 3.3.3 Java/Windows jars'}
$classpath=$libraries -join ';'
if (!$SourceRoot) { $SourceRoot=Join-Path $ProjectRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion' }
& "$JdkHome/bin/javac.exe" --release 21 -cp $classpath -d "$OutputRoot/classes" "$PSScriptRoot/HitEffectsGpuCheck.java" "$SourceRoot/HitEffectsState.java" "$SourceRoot/HitGroundPath.java" "$SourceRoot/HitGroundGeometry.java"
if($LASTEXITCODE -ne 0){throw 'Hit Effects GPU fixture compilation failed'}
& "$JdkHome/bin/java.exe" -cp "$OutputRoot/classes;$classpath" HitEffectsGpuCheck $ShaderRoot $MinecraftJar $OutputRoot
if($LASTEXITCODE -ne 0){throw 'Hit Effects GPU fixture failed'}
