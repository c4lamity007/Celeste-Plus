param(
    [Parameter(Mandatory = $true)][string]$ProjectRoot,
    [Parameter(Mandatory = $true)][string]$MinecraftJar,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [string]$JdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$DependencyCache = 'C:\Users\c4lam\.gradle\caches\modules-2\files-2.1'
)
$ErrorActionPreference = 'Stop'
$resolvedProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
$resolvedMinecraft = (Resolve-Path -LiteralPath $MinecraftJar).Path
$resources = Join-Path $resolvedProject 'src\main\resources'
$libraries = @(rg --files (Join-Path $DependencyCache 'org.lwjgl') -g '*.jar' | Where-Object {
    $_ -match '[\\/](lwjgl|lwjgl-glfw|lwjgl-opengl|lwjgl-stb)[\\/]3\.3\.3[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^lwjgl(?:-glfw|-opengl|-stb)?-3\.3\.3(?:-natives-windows)?\.jar$'
})
if ($libraries.Count -ne 8) { throw 'Expected eight cached LWJGL 3.3.3 Windows jars.' }
$classes = Join-Path $OutputRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$classpath = $libraries -join ';'
$qa = Join-Path $resolvedProject 'tools\qa'
& (Join-Path $JdkHome 'bin\javac.exe') --release 21 -classpath $classpath -d $classes (Join-Path $qa 'ImpactCheck.java') (Join-Path $qa 'ChamsAuroraCheck.java') (Join-Path $PSScriptRoot 'ChamsMaterialCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Chams material harness compilation failed.' }
& (Join-Path $JdkHome 'bin\java.exe') -Xmx768m -classpath "$classes;$classpath" ChamsMaterialCheck $resources $resolvedMinecraft $OutputRoot 2>&1 | Tee-Object -FilePath (Join-Path $OutputRoot 'gpu-check.log')
if ($LASTEXITCODE -ne 0) { throw 'Chams material GPU checks failed.' }
