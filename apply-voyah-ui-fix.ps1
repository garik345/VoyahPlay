# Applies only the UI changes to the already updated 0.1.19 source tree.
# Compatible with Windows PowerShell 5.1. Does not access the car or root helper.
[CmdletBinding()]
param([string]$ProjectRoot)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath
$utf8 = New-Object System.Text.UTF8Encoding($false)
$activityPath = 'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'
$guard = '        if (!com.shilapi.xcertplay.voyah.VoyahSettingsVisibility.isVisible(key.name)) return'
$marker = '<!-- voyah-ui-cleanup-0.1.20 -->'
$changes = New-Object 'System.Collections.Generic.Dictionary[string,byte[]]'

function Read-Source([string]$relative) {
    $path = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source file: $relative" }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

function Add-Text([string]$relative, [string]$text) {
    $changes[$relative] = $utf8.GetBytes($text)
}

$activity = Read-Source $activityPath
foreach ($required in @('enum class SettingsCategory', 'enum class SettingsSection', 'VOYAH_CONTROLS', 'VOYAH_HELPER')) {
    if (-not $activity.Contains($required)) {
        throw 'This update needs the new VoyahPlay 0.1.19 settings sources. No files were changed.'
    }
}
$android = Read-Source 'automotive/build.gradle.kts'
if ($android -notmatch 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.(19|20)"') {
    throw 'Expected VoyahPlay 0.1.19 or 0.1.20 sources. No files were changed.'
}
$nl = if ($activity.Contains("`r`n")) { "`r`n" } else { "`n" }
if (-not $activity.Contains($guard.Trim())) {
    # The same card builder is used to construct the search index.
    $anchor = '        if (settingsSectionFilter?.contains(key) == false) return'
    if ([regex]::Matches($activity, [regex]::Escape($anchor)).Count -ne 1) {
        throw 'Settings card builder changed. No files were changed.'
    }
    $activity = $activity.Replace($anchor, $guard + $nl + $anchor)
}
# Remove any leftover direct entry points that bypass filteredSection.
$activity = [regex]::Replace($activity,
    '(?m)^\s*(?:if \([^\r\n]*\) )?bydAdbSettings\(content\)[^\r\n]*(?:\r?\n)?', '')
$activity = [regex]::Replace($activity,
    '(?m)^[ \t]*advancedVehicleDataSettings\(content\)[^\r\n]*(?:\r?\n)?', '')
# First-time/old guide links open the existing iPhone connection page.
$activity = $activity.Replace('"setup" -> setupGuide(content)', '"setup" -> connectionSetup(content)')
# This Android-12-only upstream switch controls a BYD call overlay.
$activity = [regex]::Replace($activity,
    '(?m)^([ \t]*)if \(Build\.VERSION\.SDK_INT == 32\) \{\r?\n\1    toggle\(card, getString\(R\.string\.settings_hide_byd_call_popup\),\r?\n\1        getString\(R\.string\.settings_hide_byd_call_popup_description\),\r?\n\1        AirPlayPersistence\.loadHideBydCallPopup\(this\)\) \{\r?\n\1        AirPlayPersistence\.saveHideBydCallPopup\(this, it\)\r?\n\1    \}\r?\n\1\}\r?\n', '')
Add-Text $activityPath $activity

foreach ($module in @('automotive', 'mobile')) {
    $relative = "$module/build.gradle.kts"
    $text = Read-Source $relative
    if ($text -match 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.19"') {
        $text = [regex]::Replace($text, '(versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.)19"', '${1}20"')
        $codes = [regex]::Matches($text, '(?m)^([ \t]*versionCode\s*=\s*)(\d+)([ \t]*)$')
        if ($codes.Count -ne 1) { throw "Ambiguous versionCode in $relative. No files were changed." }
        $m = $codes[0]
        $newLine = $m.Groups[1].Value + ([int]$m.Groups[2].Value + 1) + $m.Groups[3].Value
        $text = $text.Remove($m.Index, $m.Length).Insert($m.Index, $newLine)
        Add-Text $relative $text
    } elseif ($text -notmatch 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.20"') {
        throw "Unexpected version in $relative. No files were changed."
    }
}

$payload = Join-Path $PSScriptRoot 'payload'
foreach ($relative in @(
    'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahControlIcon.kt',
    'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahSettingsVisibility.kt',
    'common/src/main/res/drawable/ic_dp_car.xml',
    'common/src/main/res/raw/ic_car_home.png'
)) {
    $source = Join-Path $payload $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing update payload: $relative" }
    $changes[$relative] = [System.IO.File]::ReadAllBytes($source)
}

$readme = Read-Source 'README.md'
if (-not $readme.Contains($marker)) {
    $notes = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CHANGELOG-0.1.20.md'), [System.Text.Encoding]::UTF8)
    Add-Text 'README.md' ($readme.TrimEnd() + $nl + $nl + $notes.Trim() + $nl)
}

# Validate everything above before touching the source tree. Keep an exact local backup.
$backup = Join-Path $ProjectRoot ('build/voyah-ui-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$written = New-Object 'System.Collections.Generic.List[string]'
$created = New-Object 'System.Collections.Generic.List[string]'
try {
    foreach ($entry in $changes.GetEnumerator()) {
        $target = Join-Path $ProjectRoot $entry.Key
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $old = [System.IO.File]::ReadAllBytes($target)
            if ([System.Convert]::ToBase64String($old) -eq [System.Convert]::ToBase64String($entry.Value)) { continue }
            $copy = Join-Path $backup $entry.Key
            [System.IO.Directory]::CreateDirectory((Split-Path -Parent $copy)) | Out-Null
            [System.IO.File]::WriteAllBytes($copy, $old)
        } else { $created.Add($target) }
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
        $written.Add($entry.Key)
        [System.IO.File]::WriteAllBytes($target, $entry.Value)
        Write-Host ('Updated ' + $entry.Key)
    }
} catch {
    foreach ($relative in $written) {
        $copy = Join-Path $backup $relative
        if (Test-Path -LiteralPath $copy -PathType Leaf) {
            [System.IO.File]::WriteAllBytes((Join-Path $ProjectRoot $relative), [System.IO.File]::ReadAllBytes($copy))
        }
    }
    foreach ($path in $created) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path } }
    throw
}
if ($written.Count -eq 0) { Write-Host 'VoyahPlay UI 0.1.20 is already applied.' }
else { Write-Host ('Source backup: ' + $backup) }
Write-Host 'Ready. Build automotive Debug or Release, then reconnect the iPhone to refresh the car icon.'
