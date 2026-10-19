[CmdletBinding()]
param([string]$ProjectRoot, [switch]$DryRun)
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
# Read the shipped plan directly. Do not rewrite JSON or invoke another installer.
$voyahPlanRoot = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'edits.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if ($null -eq $voyahPlanRoot) { throw 'Empty edits.json. No files were changed.' }
$plan = @($voyahPlanRoot)
if ($null -ne $voyahPlanRoot.PSObject.Properties['files']) {
    $plan = @($voyahPlanRoot.files)
} elseif ($null -ne $voyahPlanRoot.PSObject.Properties['value'] -and
        $null -eq $voyahPlanRoot.PSObject.Properties['path']) {
    $plan = @($voyahPlanRoot.value)
}
if ($plan.Count -eq 0) { throw 'No file entries in edits.json. No files were changed.' }
$voyahSeenPaths = @{}
$voyahEntryNumber = 0
foreach ($file in $plan) {
    $voyahEntryNumber++
    if ($null -eq $file -or $null -eq $file.PSObject.Properties['path'] -or
            $null -eq $file.PSObject.Properties['edits']) {
        throw "Invalid edits.json entry $voyahEntryNumber. Each file must have path and edits. No files were changed."
    }
    if ([string]::IsNullOrWhiteSpace([string]$file.path) -or @($file.edits).Count -eq 0) {
        throw "Empty path or edits in plan entry $voyahEntryNumber. No files were changed."
    }
    if ($voyahSeenPaths.ContainsKey([string]$file.path)) {
        throw "Duplicate source path in edits.json: $($file.path). No files were changed."
    }
    $voyahSeenPaths[[string]$file.path] = $true
    $original = Text-Source $file.path
    $text = $original.Replace("`r`n", "`n")
    if (([string]$file.path).Replace('\', '/') -eq 'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt') {
        $voyahKnownActivityHashes = @(
            '9251eb44d13928940130e5acf120f64ecd111a27444452d5149ced9b1a36d8b5',
            'ee5b33012710f86b8046c61233b9ffa6ccd84a080ada51451a68a676e5d63fc1'
        )
        $voyahActivityHash = Hash-Bytes ($utf8.GetBytes($text))
        if ($voyahKnownActivityHashes -notcontains $voyahActivityHash) {
            throw "DiPlayActivity.kt differs from the uploaded source. SHA256: $voyahActivityHash. No files were changed."
        }
    }
    $voyahEditNumber = 0
    foreach ($edit in @($file.edits)) {
        $voyahEditNumber++
        if ($null -eq $edit -or $null -eq $edit.PSObject.Properties['old'] -or
                $null -eq $edit.PSObject.Properties['new']) {
            throw "Invalid edit $voyahEditNumber in $($file.path). Expected old and new. No files were changed."
        }
        $voyahAnchorBefore = ([string]$edit.old).Replace("`r`n", "`n")
        $voyahAnchorAfter = ([string]$edit.new).Replace("`r`n", "`n")
        if ([string]::IsNullOrEmpty($voyahAnchorBefore) -or [string]::IsNullOrEmpty($voyahAnchorAfter)) {
            throw "Empty edit $voyahEditNumber in $($file.path). No files were changed."
        }
        if ($text.Contains($voyahAnchorAfter)) { continue }
        $count = [regex]::Matches($text, [regex]::Escape($voyahAnchorBefore)).Count
        if ($count -ne 1) { throw "Unknown or modified source in $($file.path), edit $voyahEditNumber. Expected one anchor, found $count. No files were changed." }
        $text = $text.Replace($voyahAnchorBefore, $voyahAnchorAfter)
    }
    if ($original.Contains("`r`n")) { $text = $text.Replace("`n", "`r`n") }
    $changes[$file.path] = $utf8.GetBytes($text)
}
# SHA256.json is the original Ultra package manifest. Ignore unlisted files
# left in payload by UI/audio patches. Only these 33 text files belong to 0.1.23.
$payload = Join-Path $PSScriptRoot 'payload'
$voyahChecksumPath = Join-Path $PSScriptRoot 'SHA256.json'
if (-not (Test-Path -LiteralPath $payload -PathType Container)) { throw 'Missing payload directory. No files were changed.' }
if (-not (Test-Path -LiteralPath $voyahChecksumPath -PathType Leaf)) { throw 'Missing Ultra SHA256.json manifest. No files were changed.' }
$voyahChecksumManifest = [System.IO.File]::ReadAllText($voyahChecksumPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
if ($null -eq $voyahChecksumManifest) { throw 'Empty Ultra checksum manifest. No files were changed.' }
$voyahPayloadEntries = @($voyahChecksumManifest.PSObject.Properties | Where-Object {
    $_.Name.StartsWith('payload/', [System.StringComparison]::Ordinal)
} | Sort-Object Name)
if ($voyahPayloadEntries.Count -ne 33) {
    throw "Wrong Ultra 0.1.23 payload manifest: expected 33 files, found $($voyahPayloadEntries.Count). No files were changed."
}
$voyahListedPayload = @{}
foreach ($voyahPayloadEntry in $voyahPayloadEntries) {
    $relative = $voyahPayloadEntry.Name.Substring('payload/'.Length)
    if ([string]::IsNullOrWhiteSpace($relative) -or [System.IO.Path]::IsPathRooted($relative) -or
            $relative.Contains('\') -or $relative -match '(^|/)\.\.(/|$)' -or
            $relative -notmatch '\.(kt|xml)$') {
        throw "Invalid Ultra payload path: $relative. No files were changed."
    }
    $voyahExpectedHash = [string]$voyahPayloadEntry.Value
    if ($voyahExpectedHash -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid Ultra checksum for $relative. No files were changed." }
    $voyahListedPayload[$relative] = $true
    $voyahPayloadSource = Join-Path $payload $relative
    if (-not (Test-Path -LiteralPath $voyahPayloadSource -PathType Leaf)) { throw "Missing Ultra payload file: $relative. No files were changed." }
    $voyahPayloadText = [System.IO.File]::ReadAllText($voyahPayloadSource, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")
    $voyahActualHash = Hash-Bytes ($utf8.GetBytes($voyahPayloadText))
    if ($voyahActualHash -ne $voyahExpectedHash) { throw "Ultra payload checksum mismatch: $relative. No files were changed." }
    $target = Project-Path $relative
    if ($changes.ContainsKey($relative)) { throw "Duplicate Ultra source and payload path: $relative. No files were changed." }
    if (Test-Path -LiteralPath $target -PathType Leaf) {
        # Preserve existing equivalent text, including its original line endings.
        $voyahInstalledText = [System.IO.File]::ReadAllText($target, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n")
        if ((Hash-Bytes ($utf8.GetBytes($voyahInstalledText))) -ne $voyahExpectedHash) {
            throw "A different Ultra file already exists: $relative. No files were changed."
        }
        continue
    }
    $changes[$relative] = [System.IO.File]::ReadAllBytes($voyahPayloadSource)
}
$voyahIgnoredPayloadCount = 0
foreach ($source in Get-ChildItem -LiteralPath $payload -Recurse -File) {
    $voyahCandidatePath = $source.FullName.Substring($payload.Length).TrimStart('\', '/').Replace('\', '/')
    if (-not $voyahListedPayload.ContainsKey($voyahCandidatePath)) { $voyahIgnoredPayloadCount++ }
}
Write-Host ('Ultra payload verified: 33 files; unrelated payload files ignored: ' + $voyahIgnoredPayloadCount)
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
if ($DryRun) {
    foreach ($voyahPendingFile in $pending) { Write-Host ('Would update ' + $voyahPendingFile.path) }
    Write-Host ('Preflight passed: ' + $pending.Count + ' files. No project files were changed.')
    return
}
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
