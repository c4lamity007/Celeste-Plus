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
function SourceFile([string]$relative) {
    $local = Join-Path $source $relative
    if (Test-Path -LiteralPath $local) { return $local }
    return Join-Path $BaselineSource $relative
}
$files = @(
    (SourceFile 'gui/model/GuiSetting.java'),
    (SourceFile 'gui/model/GuiModule.java'),
    (SourceFile 'gui/model/AmbientEffectsSettings.java'),
    (SourceFile 'gui/AmbientEffectsEditorLayout.java'),
    (SourceFile 'gui/AmbientEffectsEditorState.java'),
    (SourceFile 'gui/ColorPickerControl.java'),
    (SourceFile 'gui/PopupPlacement.java'),
    (SourceFile 'module/ModuleManager.java'),
    (Join-Path $PSScriptRoot 'AmbientLayersUiCheck.java')
)
$stubFiles = @{}
$baselineQa = [IO.Path]::GetFullPath((Join-Path $BaselineSource '../../../../../../tools/qa'))
foreach ($stubRoot in @((Join-Path $baselineQa 'ambient-layers-stubs'), (Join-Path $PSScriptRoot 'ambient-layers-stubs'))) {
    if (Test-Path -LiteralPath $stubRoot) {
        foreach ($file in Get-ChildItem -LiteralPath $stubRoot -Recurse -Filter '*.java') {
            $stubFiles[[IO.Path]::GetRelativePath($stubRoot, $file.FullName)] = $file.FullName
        }
    }
}
$files += $stubFiles.Values
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $out @files
if ($LASTEXITCODE -ne 0) { throw 'Ambient editor test compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -ea -cp $out dev.c4lam.celeste.gui.AmbientLayersUiCheck (Join-Path $out 'config')
if ($LASTEXITCODE -ne 0) { throw 'Ambient editor checks failed' }
