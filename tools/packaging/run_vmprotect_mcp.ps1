param(
    [Parameter(Mandatory=$true)][string]$InputDirectory,
    [Parameter(Mandatory=$true)][string]$ProjectTemplate,
    [Parameter(Mandatory=$true)][string]$McpPluginDirectory,
    [Parameter(Mandatory=$true)][string]$McpPython,
    [Parameter(Mandatory=$true)][string]$ReviewedConfig,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateSet('historical-7','auth-v2-14')][string]$Profile = 'historical-7',
    [ValidateRange(60,900)][int]$CompileTimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'The installed VMP Lite MCP driver needs an interactive Windows desktop.' }
if ($env:CERT_PFX_BASE64 -or $env:CERT_PASSWORD -or $env:CELESTE_NATIVE_RELEASE_PRIVATE_KEY) { throw 'Compilation must run without signing/publication secrets.' }
$taskInput = (Get-Item -LiteralPath $InputDirectory).FullName
$taskTemplate = (Get-Item -LiteralPath $ProjectTemplate).FullName
$taskPlugin = (Get-Item -LiteralPath $McpPluginDirectory).FullName
$taskPython = (Get-Item -LiteralPath $McpPython).FullName
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw 'Choose a fresh private MCP output directory.' }
$taskConfig = Get-Content -LiteralPath $ReviewedConfig -Raw | ConvertFrom-Json
$taskProtector = (Get-Item -LiteralPath $taskConfig.executable).FullName
if ((Get-FileHash -LiteralPath $taskProtector).Hash.ToLowerInvariant() -ne $taskConfig.executable_sha256) { throw 'The reviewed VMProtect executable changed.' }
$taskManifest = Get-Content -LiteralPath (Join-Path $taskPlugin '.codex-plugin/plugin.json') -Raw | ConvertFrom-Json
if ($taskManifest.name -ne 'vmprotect-control' -or $taskManifest.version -notlike '0.2.*') { throw 'Use the reviewed 0.2 MCP bridge with bounded compilation support.' }
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskAliases = Join-Path $taskOutput 'input'
& $taskPython (Join-Path $PSScriptRoot 'prepare_vmprotect_map.py') prepare --binary (Join-Path $taskInput 'Celeste+.exe') --original-map (Join-Path $taskInput 'Celeste+.map') --candidate (Join-Path $taskInput 'candidate.json') --output-directory $taskAliases --profile $Profile
if ($LASTEXITCODE -ne 0) { throw 'Matching private input/map preparation failed.' }
# Give this server only the explicitly selected input/template roots. The original
# user's trust file is never broadened or overwritten.
$taskJobRoot = Join-Path $taskOutput 'jobs'
New-Item -ItemType Directory -Path $taskJobRoot | Out-Null
$taskScopedConfig = Join-Path $taskOutput 'mcp-config.json'
@{executable=$taskProtector; executable_sha256=$taskConfig.executable_sha256; work_root=$taskJobRoot; allowed_roots=@($taskAliases,[IO.Path]::GetDirectoryName($taskTemplate))} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskScopedConfig -Encoding utf8
$taskEvidence = Join-Path $taskOutput 'compile-evidence.json'
& $taskPython (Join-Path $taskPlugin 'scripts/compile_job.py') --config $taskScopedConfig --input (Join-Path $taskAliases 'Celeste+.exe') --template $taskTemplate --evidence $taskEvidence --timeout $CompileTimeoutSeconds
if ($LASTEXITCODE -ne 0) { throw 'MCP compilation was not confirmed. Inspect preserved evidence and job files; do not retry the same compile.' }
$taskResult = Get-Content -LiteralPath $taskEvidence -Raw | ConvertFrom-Json
if ($taskResult.status -ne 'compiled-pending-independent-review' -or $taskResult.protection_verified -ne $false) { throw 'Unexpected MCP evidence status.' }
$taskBinary = (Get-Item -LiteralPath $taskResult.output.output_path).FullName
if (-not $taskBinary.StartsWith($taskJobRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'MCP output escaped this private job root.' }
if ((Get-FileHash -LiteralPath $taskBinary).Hash.ToLowerInvariant() -ne $taskResult.output.output_sha256) { throw 'MCP output changed.' }
@{status='compiled-pending-independent-review'; output=$taskBinary; sha256=$taskResult.output.output_sha256; evidence=$taskEvidence; protection_verified=$false} | ConvertTo-Json
# The approved adapter must independently verify transformed code and issue a
# genuine receipt next. This helper never creates or approves that receipt.
