param(
    [Parameter(Mandatory=$true)][ValidateSet('Prepare','Sign')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$Bundle,
    [Parameter(Mandatory=$true)][string]$BundleSha256,
    [Parameter(Mandatory=$true)][string]$ReleaseId,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateSet('Pfx','Store')][string]$SigningProvider = 'Pfx'
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'Native production signing requires Windows PowerShell 7.' }
if ($ReleaseId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$') { throw 'Invalid native release ID.' }
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($Mode -eq 'Prepare') {
    if ($env:CERT_PFX_BASE64 -or $env:CERT_PASSWORD -or $env:CELESTE_NATIVE_RELEASE_PRIVATE_KEY) { throw 'Build/protection must run in a step without signing or publication secrets.' }
    if (Test-Path -LiteralPath $taskOutput) { throw 'Choose a fresh protected release output directory.' }
    New-Item -ItemType Directory -Path $taskOutput | Out-Null
} elseif (-not (Test-Path -LiteralPath $taskOutput -PathType Container)) { throw 'Prepare the protected candidates before signing.' }
$taskBundleEvidence = Join-Path $taskOutput "verified-bundle-$Mode.json"
& python (Join-Path $PSScriptRoot 'native_release_bundle.py') --archive $Bundle --sha256 $BundleSha256 --destination (Join-Path $taskOutput "private-toolchain-$Mode") --output $taskBundleEvidence
if ($LASTEXITCODE -ne 0) { throw 'The reviewed protector bundle is unavailable or unapproved. Nothing was executed.' }
$taskTools = Get-Content -LiteralPath $taskBundleEvidence -Raw | ConvertFrom-Json
if ($Mode -eq 'Prepare') {
    $taskPrepared = @()
    foreach ($taskProduct in @('celeste_plus')) {
        $taskName = 'Celeste+.exe'
        $taskDirectory = Join-Path $taskOutput $taskProduct
        $taskCandidate = Join-Path $taskDirectory 'candidate'
        New-Item -ItemType Directory -Path $taskDirectory | Out-Null
        & (Join-Path $PSScriptRoot 'build-windows-native-candidate.ps1') -Product $taskProduct -OutputDirectory $taskCandidate -MapForProtection *> (Join-Path $taskDirectory 'candidate.log')
        $taskInput = Join-Path $taskCandidate $taskName
        $taskFinal = Join-Path $taskDirectory $taskName
        $taskReceipt = Join-Path $taskDirectory 'protection-receipt.json'
        $taskProductTools = $taskTools.products.$taskProduct
        $taskAdapterArgs = @{ Product=$taskProduct; ReleaseId=$ReleaseId; InputBinary=$taskInput; OutputBinary=$taskFinal; ReceiptPath=$taskReceipt; Protector=$taskProductTools.protector; Configuration=$taskProductTools.configuration; Verifier=$taskProductTools.verifier; RepositoryRoot=$taskRoot }
        & $taskTools.adapter -Mode Protect @taskAdapterArgs *> (Join-Path $taskDirectory 'protect.log')
        if (-not $?) { throw 'Approved protection adapter failed.' }
        if (-not (Test-Path -LiteralPath $taskFinal -PathType Leaf) -or (Get-FileHash -LiteralPath $taskInput).Hash -eq (Get-FileHash -LiteralPath $taskFinal).Hash) { throw 'Native protector did not produce distinct output.' }
        if ((Get-AuthenticodeSignature -LiteralPath $taskFinal).Status -ne 'NotSigned') { throw 'The Protect phase must return an unsigned executable.' }
        $taskPrepared += [ordered]@{product=$taskProduct; release_id=$ReleaseId; input_sha256=(Get-FileHash -LiteralPath $taskInput).Hash; protected_sha256=(Get-FileHash -LiteralPath $taskFinal).Hash}
    }
    $taskPrepared | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskOutput 'protected-candidates.json') -Encoding utf8
    return
}
$taskPrepared = @(Get-Content -LiteralPath (Join-Path $taskOutput 'protected-candidates.json') -Raw | ConvertFrom-Json)
if ($taskPrepared.Count -ne 1) { throw 'The Celeste+ native candidate must be prepared before signing.' }
$taskRequired = @('CELESTE_SIGNING_CERT_THUMBPRINT')
if ($SigningProvider -eq 'Pfx') { $taskRequired += @('CERT_PFX_BASE64','CERT_PASSWORD') }
foreach ($taskSecret in $taskRequired) {
    if (-not [Environment]::GetEnvironmentVariable($taskSecret)) { throw "Missing required signing material: $taskSecret. Production signing has no unsigned fallback." }
}
$taskThumbprint = $env:CELESTE_SIGNING_CERT_THUMBPRINT.ToUpperInvariant()
if ($taskThumbprint -notmatch '^[A-F0-9]{40}$') { throw 'Pin the reviewed signing certificate thumbprint.' }
$taskSignTool = @(Get-ChildItem -Path "${env:ProgramFiles(x86)}/Windows Kits/10/bin/*/x64/signtool.exe" -File | Sort-Object FullName -Descending)[0].FullName
if (-not $taskSignTool) { throw 'Install the Windows SDK signing tools on the runner.' }
$taskCertificatePath = "Cert:\CurrentUser\My\$taskThumbprint"
if ($SigningProvider -eq 'Pfx' -and (Test-Path -LiteralPath $taskCertificatePath)) { throw 'PFX signing expects a clean ephemeral certificate store.' }
$taskPfx = Join-Path $taskOutput 'private-signing.pfx'
$taskExistingCertificates = @(Get-ChildItem -LiteralPath 'Cert:\CurrentUser\My' | ForEach-Object { $_.Thumbprint })
$taskCertificates = @()
Push-Location $taskRoot
try {
    if ($SigningProvider -eq 'Pfx') {
        [IO.File]::WriteAllBytes($taskPfx, [Convert]::FromBase64String($env:CERT_PFX_BASE64))
        $taskPassword = ConvertTo-SecureString -String $env:CERT_PASSWORD -AsPlainText -Force
        # Non-exportable import; no password/private key in command arguments.
        $taskCertificates = @(Import-PfxCertificate -FilePath $taskPfx -CertStoreLocation 'Cert:\CurrentUser\My' -Password $taskPassword)
        $taskCertificate = @($taskCertificates | Where-Object { $_.Thumbprint -eq $taskThumbprint -and $_.HasPrivateKey })
        Remove-Item -LiteralPath $taskPfx -Force
        $taskPassword.Dispose()
    } else {
        # An explicitly provisioned CSP/KSP identity may keep its key on hardware.
        # This branch neither exports nor deletes that pre-existing identity.
        $taskCertificate = @(Get-Item -LiteralPath $taskCertificatePath -ErrorAction Stop | Where-Object { $_.HasPrivateKey })
    }
    if ($taskCertificate.Count -ne 1 -or $taskCertificate[0].NotAfter -lt (Get-Date) -or $taskCertificate[0].NotBefore -gt (Get-Date)) { throw 'No pinned current Windows signing identity with a private key.' }
    $env:CERT_PFX_BASE64 = $null
    $env:CERT_PASSWORD = $null
    $taskArtifacts = @()
    foreach ($taskProduct in @('celeste_plus')) {
        $taskName = 'Celeste+.exe'
        $taskDirectory = Join-Path $taskOutput $taskProduct
        $taskCandidate = Join-Path $taskDirectory 'candidate'
        $taskInput = Join-Path $taskCandidate $taskName
        $taskFinal = Join-Path $taskDirectory $taskName
        $taskReceipt = Join-Path $taskDirectory 'protection-receipt.json'
        $taskProductTools = $taskTools.products.$taskProduct
        $taskAdapterArgs = @{ Product=$taskProduct; ReleaseId=$ReleaseId; InputBinary=$taskInput; OutputBinary=$taskFinal; ReceiptPath=$taskReceipt; Protector=$taskProductTools.protector; Configuration=$taskProductTools.configuration; Verifier=$taskProductTools.verifier; RepositoryRoot=$taskRoot }
        $taskPrior = @($taskPrepared | Where-Object { $_.product -eq $taskProduct -and $_.release_id -eq $ReleaseId })
        if ($taskPrior.Count -ne 1 -or (Get-FileHash -LiteralPath $taskInput).Hash -ne $taskPrior[0].input_sha256 -or (Get-FileHash -LiteralPath $taskFinal).Hash -ne $taskPrior[0].protected_sha256) { throw 'Prepared input or protected executable changed before signing.' }
        if ((Get-AuthenticodeSignature -LiteralPath $taskFinal).Status -ne 'NotSigned') { throw 'The prepared candidate is already signed; do not sign different bytes.' }
        & $taskSignTool sign /sha1 $taskThumbprint /s My /fd SHA256 /tr 'http://timestamp.digicert.com' /td SHA256 $taskFinal *> (Join-Path $taskDirectory 'sign.log')
        if ($LASTEXITCODE -ne 0) { throw 'Authenticode signing failed; no unsigned fallback.' }
        & $taskSignTool verify /pa /all /tw $taskFinal *> (Join-Path $taskDirectory 'signature.log')
        if ($LASTEXITCODE -ne 0) { throw 'Authenticode verification failed.' }
        $taskSignature = Get-AuthenticodeSignature -LiteralPath $taskFinal
        if ($taskSignature.Status -ne 'Valid' -or $taskSignature.SignerCertificate.Thumbprint -ne $taskThumbprint -or -not $taskSignature.TimeStamperCertificate) { throw 'Final signature or timestamp does not match the reviewed signing identity.' }
        $taskFinalHash = (Get-FileHash -LiteralPath $taskFinal -Algorithm SHA256).Hash.ToLowerInvariant()
        # VerifyFinal may only inspect these final signed bytes and write the
        # real verifier's evidence. It must not mutate or re-sign the executable.
        & $taskTools.adapter -Mode VerifyFinal @taskAdapterArgs *> (Join-Path $taskDirectory 'verify-protection.log')
        if (-not $? -or (Get-FileHash -LiteralPath $taskFinal).Hash.ToLowerInvariant() -ne $taskFinalHash) { throw 'Protection verification failed or modified the final signed bytes.' }
        & python (Join-Path $PSScriptRoot 'native_protection_gate.py') --binary $taskFinal --receipt $taskReceipt --release-id $ReleaseId --product $taskProduct --platform windows-x64 *> (Join-Path $taskDirectory 'protection-gate.json')
        if ($LASTEXITCODE -ne 0) { throw 'Native publication protection gate rejected the final signed bytes.' }
        & python (Join-Path $PSScriptRoot 'windows_release_metadata.py') --binary $taskFinal --forbid-prefix $taskRoot --forbid-prefix ([Environment]::GetFolderPath('UserProfile')) *> (Join-Path $taskDirectory 'metadata.json')
        if ($LASTEXITCODE -ne 0) { throw 'Protected signed binary failed the metadata gate.' }
        $taskArtifacts += [ordered]@{product=$taskProduct; platform='windows-x64'; release_id=$ReleaseId; binary=$taskFinal; receipt=$taskReceipt; sha256=$taskFinalHash; size=(Get-Item -LiteralPath $taskFinal).Length; signer_thumbprint=$taskThumbprint}
    }
    $taskArtifacts | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskOutput 'release-candidates.json') -Encoding utf8
} finally {
    $env:CERT_PFX_BASE64 = $null
    $env:CERT_PASSWORD = $null
    if (Test-Path -LiteralPath $taskPfx) { Remove-Item -LiteralPath $taskPfx -Force }
    foreach ($taskImportedCertificate in $taskCertificates) {
        if ($taskImportedCertificate.Thumbprint -notin $taskExistingCertificates) {
            Remove-Item -LiteralPath $taskImportedCertificate.PSPath -DeleteKey -Force
        }
    }
    Pop-Location
}
