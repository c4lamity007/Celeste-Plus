param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$MinecraftJar,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$ShaderRoot='',
    [string]$JdkHome='C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
$projectPath=(Resolve-Path -LiteralPath $ProjectRoot).Path
$gamePath=(Resolve-Path -LiteralPath $MinecraftJar).Path
if(-not $ShaderRoot){$ShaderRoot=Join-Path $projectPath 'src/main/resources/assets/celeste/shaders/core'}
$shaderPath=(Resolve-Path -LiteralPath $ShaderRoot).Path
$outputPath=[IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path "$outputPath/classes" | Out-Null
$cache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.lwjgl'
$libraries=@(rg --files $cache -g '*.jar' | Where-Object {
    $_ -match '[\\/]3\.3\.3[\\/]' -and [IO.Path]::GetFileName($_) -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$'
})
if($libraries.Count -ne 6){throw 'Expected six cached LWJGL 3.3.3 Java/Windows jars; no downloads attempted.'}
$classpath=$libraries -join ';'
& "$JdkHome/bin/javac.exe" --release 21 -cp $classpath -d "$outputPath/classes" "$projectPath/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualAtmosphere.java" "$PSScriptRoot/GroundShadersCheck.java"
if($LASTEXITCODE -ne 0){throw 'Ground shader fixture compilation failed'}
& "$JdkHome/bin/java.exe" -cp "$outputPath/classes;$classpath" GroundShadersCheck $shaderPath $gamePath $outputPath
if($LASTEXITCODE -ne 0){throw 'Ground shader GPU check failed'}
