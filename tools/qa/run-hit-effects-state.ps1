param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Output = "build/module-qa/hit-effects-state",
    [string]$PaletteSource = '',
    [string]$ProjectRoot = '',
    [string]$SourceRoot = ''
)
$ErrorActionPreference = 'Stop'
$repo = if ($ProjectRoot) { (Resolve-Path $ProjectRoot).Path } else { (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path }
if (!$JavaHome) { throw 'JavaHome is required' }
$out = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $repo $Output }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$baseSource=Join-Path $repo 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
$source = if ($SourceRoot) { $SourceRoot } else { $baseSource }
function Hit-Source([string]$name) { $path=Join-Path $source $name; if(Test-Path -LiteralPath $path){return $path}; return (Join-Path $baseSource $name) }
if (!$PaletteSource) { $PaletteSource=Join-Path $baseSource 'VisualAtmosphere.java' }
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $out (Hit-Source 'HitEffectsState.java') (Hit-Source 'HitGroundPath.java') (Hit-Source 'HitGroundGeometry.java') $PaletteSource (Join-Path $PSScriptRoot 'HitEffectsCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Hit Effects test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -ea -cp $out dev.c4lam.celeste.module.impl.visual.expansion.HitEffectsCheck
if ($LASTEXITCODE -ne 0) { throw 'Hit Effects checks failed' }
