param(
    [Parameter(Mandatory=$true)][string]$ProjectRoot,
    [Parameter(Mandatory=$true)][string]$OutputRoot,
    [string]$ProductionClasses='',
    [string]$StagedRoot='',
    [string]$JdkHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$groundProject=(Resolve-Path -LiteralPath $ProjectRoot).Path
if(-not $StagedRoot){$StagedRoot=$groundProject}
if(-not $ProductionClasses){$ProductionClasses=Join-Path $groundProject 'build/classes/java/main'}
$groundOutput=[IO.Path]::GetFullPath($OutputRoot)
$groundClasses=Join-Path $groundOutput 'classes'
New-Item -ItemType Directory -Force -Path $groundClasses | Out-Null
$groundCache=Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$groundAsm=@(rg --files (Join-Path $groundCache 'org.ow2.asm') -g '*.jar' | Where-Object {
    $_ -match '[\\/](asm|asm-commons)[\\/]9\.9[\\/]' -and
    [IO.Path]::GetFileName($_) -match '^asm(?:-commons)?-9\.9\.jar$'
})
if($groundAsm.Count -ne 2){throw 'Expected two cached ASM9.9 jars; no downloads attempted.'}
$groundClasspath=$groundAsm -join ';'
$groundHelpers=Join-Path $StagedRoot 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion'
$groundFeature=Join-Path $ProductionClasses 'dev/c4lam/celeste/module/impl/visual/expansion/GroundShadersFeature.class'
$groundRenderer=Join-Path $ProductionClasses 'dev/c4lam/celeste/module/impl/visual/expansion/GroundShadersRenderer.class'
if(-not(Test-Path -LiteralPath $groundFeature)-or-not(Test-Path -LiteralPath $groundRenderer)){throw 'Build actual Ground Shaders production classes first, or pass their exact ProductionClasses directory.'}
function Invoke-GroundLifecycle {
    param([string]$Executable,[string[]]$Arguments,[string]$Label)
    $lines=@(& $Executable @Arguments 2>&1);$code=$LASTEXITCODE
    $lines | Set-Content -LiteralPath (Join-Path $groundOutput "$Label.log") -Encoding utf8
    if($code -ne 0){throw "$Label failed ($code):`n$(($lines | Select-Object -Last 14 | Out-String).Trim())"}
    return $lines
}
$null=Invoke-GroundLifecycle (Join-Path $JdkHome 'bin/javac.exe') @('--release','21','-classpath',$groundClasspath,'-d',$groundClasses,
    (Join-Path $groundHelpers 'GroundShadersMath.java'),
    (Join-Path $groundProject 'src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/VisualAtmosphere.java'),
    (Join-Path $PSScriptRoot 'GroundShadersLifecycleCheck.java')) 'compile'
Invoke-GroundLifecycle (Join-Path $JdkHome 'bin/java.exe') @('-classpath',"$groundClasses;$groundClasspath",'GroundShadersLifecycleCheck',$ProductionClasses) 'lifecycle'
Get-FileHash -Algorithm SHA256 -LiteralPath $groundFeature,$groundRenderer |
    ForEach-Object { '{0} {1}' -f $_.Hash.ToLowerInvariant(),[IO.Path]::GetFileName($_.Path) } |
    Set-Content -LiteralPath (Join-Path $groundOutput 'production-class-sha256.txt') -Encoding utf8
