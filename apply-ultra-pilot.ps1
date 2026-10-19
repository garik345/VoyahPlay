[CmdletBinding()]
param([string]$ProjectRoot)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath.TrimEnd('\', '/')
if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'gradlew.bat') -PathType Leaf)) { throw 'Select the VoyahPlay project root, next to gradlew.bat.' }
$utf8 = New-Object System.Text.UTF8Encoding($false)
$changes = New-Object 'System.Collections.Generic.Dictionary[string,byte[]]'
function Project-Path([string]$relative) {
    $full = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $relative))
    $prefix = $ProjectRoot + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw "Unsafe project path: $relative" }
    return $full
}
function Text-Source([string]$relative) {
    $path = Project-Path $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source: $relative. No files were changed." }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}
function Hash-Bytes([byte[]]$data) {
    $hash = [System.Security.Cryptography.SHA256]::Create()
    try { return ([System.BitConverter]::ToString($hash.ComputeHash($data))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
}
$plan = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'edits.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
foreach ($file in $plan) {
    $original = Text-Source $file.path
    $text = $original.Replace("`r`n", "`n")
    foreach ($edit in $file.edits) {
        if ($text.Contains([string]$edit.new)) { continue }
        $count = [regex]::Matches($text, [regex]::Escape([string]$edit.old)).Count
        if ($count -ne 1) { throw "Unknown or modified source in $($file.path). Expected one anchor, found $count. No files were changed." }
        $text = $text.Replace([string]$edit.old, [string]$edit.new)
    }
    if ($original.Contains("`r`n")) { $text = $text.Replace("`n", "`r`n") }
    $changes[$file.path] = $utf8.GetBytes($text)
}
$payload = Join-Path $PSScriptRoot 'payload'
foreach ($source in Get-ChildItem -LiteralPath $payload -Recurse -File) {
    $relative = $source.FullName.Substring($payload.Length).TrimStart('\', '/').Replace('\', '/')
    $target = Project-Path $relative
    $data = [System.IO.File]::ReadAllBytes($source.FullName)
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        if ((Hash-Bytes ([System.IO.File]::ReadAllBytes($target))) -ne (Hash-Bytes $data)) {
            throw "A different file already exists: $relative. No files were changed."
        }
    }
    $changes[$relative] = $data
}
foreach ($module in @('automotive', 'mobile')) {
    $relative = "$module/build.gradle.kts"
    $text = Text-Source $relative
    $match = [regex]::Match($text, 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.(\d+)"')
    if (-not $match.Success -or [int]$match.Groups[1].Value -gt 23) { throw "Unknown/newer version in $relative. No files were changed." }
    if ([int]$match.Groups[1].Value -lt 23) {
        $text = [regex]::Replace($text, '(versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.)\d+"', '${1}23"')
        $codes = [regex]::Matches($text, '(?m)^([ \t]*versionCode\s*=\s*)(\d+)([ \t]*\r?)$')
        if ($codes.Count -ne 1) { throw "Unknown versionCode in $relative. No files were changed." }
        $code = $codes[0]
        $minimum = if ($module -eq 'automotive') { 1207 } else { 43 }
        $line = $code.Groups[1].Value + [Math]::Max([int]$code.Groups[2].Value + 1, $minimum) + $code.Groups[3].Value
        $text = $text.Remove($code.Index, $code.Length).Insert($code.Index, $line)
        $changes[$relative] = $utf8.GetBytes($text)
    }
}
$readme = Text-Source 'README.md'
if (-not $readme.Contains('<!-- voyah-ultra-pilot-0.1.23 -->')) {
    $nl = if ($readme.Contains("`r`n")) { "`r`n" } else { "`n" }
    $notes = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CHANGELOG-0.1.23.md'), [System.Text.Encoding]::UTF8).Replace("`r`n", "`n").Replace("`n", $nl)
    $changes['README.md'] = $utf8.GetBytes($readme.TrimEnd() + $nl + $nl + $notes.Trim() + $nl)
}

# Validate everything before touching project files; preserve exact originals for rollback.
$pending = New-Object 'System.Collections.Generic.List[object]'
foreach ($entry in $changes.GetEnumerator()) {
    $target = Project-Path $entry.Key
    $exists = Test-Path -LiteralPath $target -PathType Leaf
    $nextHash = Hash-Bytes $entry.Value
    if ($exists -and (Hash-Bytes ([System.IO.File]::ReadAllBytes($target))) -eq $nextHash) { continue }
    $pending.Add([pscustomobject]@{ path = $entry.Key; existed = [bool]$exists; installedSha256 = $nextHash })
}
if ($pending.Count -eq 0) { Write-Host 'Ultra pilot 0.1.23 is already applied.'; return }
$backup = Join-Path $ProjectRoot ('build/voyah-ultra-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
[System.IO.Directory]::CreateDirectory($backup) | Out-Null
foreach ($item in $pending) {
    if (-not $item.existed) { continue }
    $copy = Join-Path $backup $item.path
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $copy)) | Out-Null
    [System.IO.File]::Copy((Project-Path $item.path), $copy, $false)
}
[System.IO.File]::WriteAllText((Join-Path $backup 'restore-manifest.json'), (ConvertTo-Json -InputObject @($pending.ToArray()) -Depth 5), $utf8)
$written = New-Object 'System.Collections.Generic.List[object]'
try {
    foreach ($item in $pending) {
        $target = Project-Path $item.path
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
        $written.Add($item)
        [System.IO.File]::WriteAllBytes($target, $changes[$item.path])
        Write-Host ('Updated ' + $item.path)
    }
} catch {
    foreach ($item in $written) {
        $target = Project-Path $item.path
        if ($item.existed) { [System.IO.File]::Copy((Join-Path $backup $item.path), $target, $true) }
        elseif (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target }
    }
    throw
}
Write-Host ('Source backup: ' + $backup)
Write-Host 'Ready. Build automotive/debug. Ultra is disabled by default; enable it in Advanced, then apply and reconnect.'
