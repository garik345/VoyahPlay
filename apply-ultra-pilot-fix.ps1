# Ultra 0.1.23 adapter for the DiPlayActivity.kt uploaded on 2026-10-19.
# Extract beside the ORIGINAL apply-ultra-pilot.ps1, edits.json and payload/.
# Windows PowerShell 5.1 compatible. Does not invoke ADB or change certificates.
[CmdletBinding()]
param([string]$ProjectRoot)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-VoyahNormalizedHash([string]$VoyahText) {
    $voyahSha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $voyahBytes = [System.Text.Encoding]::UTF8.GetBytes($VoyahText.Replace("`r`n", "`n"))
        return ([BitConverter]::ToString($voyahSha.ComputeHash($voyahBytes))).Replace('-', '').ToLowerInvariant()
    } finally { $voyahSha.Dispose() }
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$voyahProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath
$voyahInstallerPath = Join-Path $PSScriptRoot 'apply-ultra-pilot.ps1'
$voyahPlanPath = Join-Path $PSScriptRoot 'edits.json'
$voyahPayloadPath = Join-Path $PSScriptRoot 'ultra-current-settings/DiPlayActivity.kt'
$voyahSourceRelative = 'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'
$voyahSourcePath = Join-Path $voyahProjectRoot $voyahSourceRelative

foreach ($voyahRequiredPath in @($voyahInstallerPath, $voyahPlanPath, $voyahPayloadPath,
        $voyahSourcePath, (Join-Path $voyahProjectRoot 'gradlew.bat'), (Join-Path $PSScriptRoot 'payload'))) {
    if (-not (Test-Path -LiteralPath $voyahRequiredPath)) {
        throw "Missing $voyahRequiredPath. Extract this fix into the project root beside the ORIGINAL Ultra package. No source files were changed."
    }
}

$voyahInputHash = '9251eb44d13928940130e5acf120f64ecd111a27444452d5149ced9b1a36d8b5'
$voyahOutputHash = 'ee5b33012710f86b8046c61233b9ffa6ccd84a080ada51451a68a676e5d63fc1'
$voyahBefore = [IO.File]::ReadAllText($voyahSourcePath, [Text.Encoding]::UTF8).Replace("`r`n", "`n")
$voyahAfter = [IO.File]::ReadAllText($voyahPayloadPath, [Text.Encoding]::UTF8).Replace("`r`n", "`n")
if ((Get-VoyahNormalizedHash $voyahAfter) -ne $voyahOutputHash) {
    throw 'The supplied settings source is damaged or modified. No source files were changed.'
}
$voyahActualHash = Get-VoyahNormalizedHash $voyahBefore
if ($voyahActualHash -ne $voyahInputHash -and $voyahActualHash -ne $voyahOutputHash) {
    throw "DiPlayActivity.kt differs from the uploaded source. Actual SHA256: $voyahActualHash. No source files were changed. Send the current source."
}

$voyahLockPath = Join-Path $PSScriptRoot 'ultra-pilot-current.lock'
$voyahLock = [IO.File]::Open($voyahLockPath, [IO.FileMode]::OpenOrCreate,
    [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
$voyahPlanWritten = $false
$voyahPlanBackupPath = $null
$voyahOriginalPlanBytes = $null
try {
    $voyahOriginalPlanBytes = [IO.File]::ReadAllBytes($voyahPlanPath)
    $voyahPlan = [IO.File]::ReadAllText($voyahPlanPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $voyahFiles = @($voyahPlan)
    if ($null -ne $voyahPlan.PSObject.Properties['files']) { $voyahFiles = @($voyahPlan.files) }
    $voyahSourceEntries = @($voyahFiles | Where-Object {
        ([string]$_.path).Replace('\', '/') -eq $voyahSourceRelative
    })
    if ($voyahSourceEntries.Count -ne 1 -or $null -eq $voyahSourceEntries[0].PSObject.Properties['edits']) {
        throw 'Unsupported edits.json: expected one DiPlayActivity.kt entry with path and edits. No source files were changed.'
    }

    # Replace ONLY this source's patch context with the exact uploaded/patched
    # source. Every other edit and payload item keeps the original checks.
    $voyahSourceEntries[0].edits = @([pscustomobject]@{ old = $voyahBefore; new = $voyahAfter })
    $voyahTemporaryPlan = ConvertTo-Json -InputObject $voyahPlan -Depth 100
    $voyahPlanBackupPath = Join-Path $PSScriptRoot ('edits.json.before-current-' + [guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllBytes($voyahPlanBackupPath, $voyahOriginalPlanBytes)
    $voyahPlanWritten = $true
    [IO.File]::WriteAllText($voyahPlanPath, $voyahTemporaryPlan, [Text.UTF8Encoding]::new($false))

    Write-Host 'Settings source verified against the uploaded DiPlayActivity.kt.'
    Write-Host 'Running the original installer: all-file preflight, backups, source edits, version and README.'
    & $voyahInstallerPath -ProjectRoot $voyahProjectRoot
} finally {
    try {
        if ($voyahPlanWritten) {
            [IO.File]::WriteAllBytes($voyahPlanPath, $voyahOriginalPlanBytes)
            if ($null -ne $voyahPlanBackupPath) { Remove-Item -LiteralPath $voyahPlanBackupPath -Force }
        }
    } finally {
        $voyahLock.Dispose()
        Remove-Item -LiteralPath $voyahLockPath -Force
    }
}
