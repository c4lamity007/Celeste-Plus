param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Output = 'build/module-qa/ambient-layers-ui',
    [string]$BaselineSource = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (!$JavaHome) { throw 'JavaHome is required' }
$out = if ([IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $repo $Output }
New-Item -ItemType Directory -Force -Path $out | Out-Null
$source = Join-Path $repo 'src/main/java/dev/c4lam/celeste'
if (!$BaselineSource) { $BaselineSource = $source }
$files = @(
    (Join-Path $source 'gui/model/GuiSetting.java'),
    (Join-Path $BaselineSource 'gui/model/GuiModule.java'),
    (Join-Path $source 'gui/model/AmbientEffectsSettings.java'),
    (Join-Path $source 'gui/AmbientEffectsEditorLayout.java'),
    (Join-Path $source 'gui/AmbientEffectsEditorState.java'),
    (Join-Path $source 'module/ModuleManager.java'),
    (Join-Path $PSScriptRoot 'AmbientLayersUiCheck.java')
)
$files += Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'ambient-layers-stubs') -Recurse -Filter '*.java' | ForEach-Object FullName
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Ambient editor test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -ea -cp $out dev.c4lam.celeste.gui.AmbientLayersUiCheck (Join-Path $out 'config')
if ($LASTEXITCODE -ne 0) { throw 'Ambient editor checks failed' }
