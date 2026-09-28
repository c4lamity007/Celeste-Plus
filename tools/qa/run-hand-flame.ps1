param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$BaselineShaderRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [string]$DependencyCache=(Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1')
)
$ErrorActionPreference='Stop'
$projectPath=(Resolve-Path -LiteralPath $ProjectRoot).Path
$baselinePath=(Resolve-Path -LiteralPath $BaselineShaderRoot).Path
$outputPath=[IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path "$outputPath/classes" | Out-Null
$libraries=@(Get-ChildItem -LiteralPath "$DependencyCache/org.lwjgl" -Recurse -File -Filter '*.jar' | Where-Object {
    $_.FullName -match '[\\/]3\.3\.3[\\/]' -and $_.Name -match '^lwjgl(-glfw|-opengl)?-3\.3\.3(-natives-windows)?\.jar$'
})
if($libraries.Count -ne 6){throw "Expected six LWJGL 3.3.3 Java/Windows jars, found $($libraries.Count)"}
$classpath=$libraries.FullName -join ';'
& "$JdkHome/bin/javac.exe" --release 21 -cp $classpath -d "$outputPath/classes" "$PSScriptRoot/HandFlameCheck.java" "$projectPath/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/HandFogTiming.java"
if($LASTEXITCODE -ne 0){throw 'Flame fixture compilation failed'}
& "$JdkHome/bin/java.exe" -cp "$outputPath/classes;$classpath" HandFlameCheck "$projectPath/src/main/resources/assets/celeste/shaders/core" $baselinePath $outputPath
if($LASTEXITCODE -ne 0){throw 'Flame GPU checks failed'}
