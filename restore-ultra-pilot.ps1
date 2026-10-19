[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Backup, [string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath.TrimEnd('\', '/')
$backupRoot = (Resolve-Path -LiteralPath $Backup).ProviderPath
$manifest = Get-Content -LiteralPath (Join-Path $backupRoot 'restore-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
function Project-Path([string]$relative) {
    $full = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $relative))
    if (-not $full.StartsWith($ProjectRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe path: $relative" }
    return $full
}
foreach ($item in $manifest) {
    $target = Project-Path $item.path
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) { throw "File missing: $($item.path). Restore cancelled." }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $item.installedSha256) { throw "File was edited after this update: $($item.path). Restore cancelled to preserve your changes." }
    if ($item.existed -and -not (Test-Path -LiteralPath (Join-Path $backupRoot $item.path) -PathType Leaf)) { throw "Missing backup: $($item.path)" }
}
foreach ($item in $manifest) {
    $target = Project-Path $item.path
    if ($item.existed) { [System.IO.File]::Copy((Join-Path $backupRoot $item.path), $target, $true) }
    else { Remove-Item -LiteralPath $target }
    Write-Host ('Restored ' + $item.path)
}
Write-Host 'Sources restored. Rebuild APK if you want to remove the pilot from the installed app.'
