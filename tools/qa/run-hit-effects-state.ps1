param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Output = "build/module-qa/hit-effects-state",
    [string]$PaletteSource = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JavaHome) { throw 'JavaHome is required' }
$out = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $repo $Output }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source = Join-Path $repo 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
if (!$PaletteSource) { $PaletteSource=Join-Path $source 'VisualAtmosphere.java' }
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $out (Join-Path $source 'HitEffectsState.java') (Join-Path $source 'HitGroundPath.java') $PaletteSource (Join-Path $PSScriptRoot 'HitEffectsCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Hit Effects test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -ea -cp $out dev.c4lam.celeste.module.impl.visual.expansion.HitEffectsCheck
if ($LASTEXITCODE -ne 0) { throw 'Hit Effects checks failed' }
