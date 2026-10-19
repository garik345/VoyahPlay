# Source-only update for the current VoyahPlay project.
[CmdletBinding()]
param([string]$ProjectRoot)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).ProviderPath
$utf8 = New-Object System.Text.UTF8Encoding($false)
$changes = New-Object 'System.Collections.Generic.Dictionary[string,byte[]]'
function Read-Source([string]$relative) {
    $path = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source: $relative" }
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}
function Add-Text([string]$relative, [string]$text) { $changes[$relative] = $utf8.GetBytes($text) }

$iconPath = 'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahControlIcon.kt'
$iconSource = Read-Source $iconPath
if (-not $iconSource.Contains('var panelScale: Float')) {
    $anchor = 'class VoyahControlIcon(context: Context, val id: String) : View(context) {'
    if ([regex]::Matches($iconSource, [regex]::Escape($anchor)).Count -ne 1) { throw 'Unknown icon renderer. No files were changed.' }
    $nl = if ($iconSource.Contains("`r`n")) { "`r`n" } else { "`n" }
    $property = @'
    /** Additional scale inside the existing hit area; bottom-row scale stays unchanged. */
    var panelScale: Float = 1f
        set(value) {
            val next = value.coerceIn(0.2f, 1f)
            if (field != next) { field = next; invalidate() }
        }
'@
    $property = $property.Replace("`r`n", "`n").Replace("`n", $nl)
    $iconSource = $iconSource.Replace($anchor, $anchor + $nl + $property)
    $scales = [regex]::Matches($iconSource, '(?m)^([ \t]*val scale = )([^\r\n]+)')
    if ($scales.Count -ne 1 -or -not $scales[0].Groups[2].Value.Contains('minOf(width / 48f, height / 55f)')) {
        throw 'Unknown icon fitting formula. No files were changed.'
    }
    $scale = $scales[0]
    $line = $scale.Groups[1].Value + '(' + $scale.Groups[2].Value + ') * panelScale'
    $iconSource = $iconSource.Remove($scale.Index, $scale.Length).Insert($scale.Index, $line)
}
if (-not $iconSource.Contains(' * panelScale')) { throw 'Incomplete icon update. No files were changed.' }
Add-Text $iconPath $iconSource

$barPath = 'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahComfortBar.kt'
$barSource = Read-Source $barPath
if (-not $barSource.Contains('panelScale = VoyahScreenGeometry.SIDE_ICON_SCALE')) {
    $anchor = '        val icon = VoyahControlIcon(activity,id)'
    if ([regex]::Matches($barSource, [regex]::Escape($anchor)).Count -ne 1) { throw 'Unknown vehicle button factory. No files were changed.' }
    $nl = if ($barSource.Contains("`r`n")) { "`r`n" } else { "`n" }
    $barSource = $barSource.Replace($anchor, $anchor + '.apply {' + $nl +
        '            if (edge != VoyahComfortLayout.Edge.BOTTOM) panelScale = VoyahScreenGeometry.SIDE_ICON_SCALE' + $nl + '        }')
}
Add-Text $barPath $barSource

$hostPath = 'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt'
$hostSource = Read-Source $hostPath
$function = [regex]::Match($hostSource, '(?s)    private fun buildContentView\(\): View \{.*?\r?\n    \}')
if (-not $function.Success) { throw 'Unknown CarPlay content builder. No files were changed.' }
$body = $function.Value
if (-not $body.Contains('VoyahFullscreenContent.wrap(this, root)')) {
    $pattern = '(?m)^([ \t]*)return root[ \t]*(\r?)$'
    if ([regex]::Matches($body, $pattern).Count -ne 1) { throw 'Unknown CarPlay content root. No files were changed.' }
    $body = [regex]::Replace($body, $pattern, '${1}return com.shilapi.xcertplay.voyah.VoyahFullscreenContent.wrap(this, root)${2}')
    $hostSource = $hostSource.Remove($function.Index, $function.Length).Insert($function.Index, $body)
}
Add-Text $hostPath $hostSource

$homePath = 'common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt'
$homeSource = Read-Source $homePath
if (-not $homeSource.Contains('setContentView(com.shilapi.xcertplay.voyah.VoyahFullscreenContent.wrap(this, root))')) {
    $pattern = '(?m)^([ \t]*)setContentView\(root\)[ \t]*(\r?)$'
    if ([regex]::Matches($homeSource, $pattern).Count -ne 1) { throw 'Unknown home/settings content root. No files were changed.' }
    $homeSource = [regex]::Replace($homeSource, $pattern, '${1}setContentView(com.shilapi.xcertplay.voyah.VoyahFullscreenContent.wrap(this, root))${2}')
}
Add-Text $homePath $homeSource

foreach ($module in @('automotive', 'mobile')) {
    $relative = "$module/build.gradle.kts"
    $text = Read-Source $relative
    $match = [regex]::Match($text, 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.(\d+)"')
    if (-not $match.Success -or [int]$match.Groups[1].Value -lt 21 -or [int]$match.Groups[1].Value -gt 22) {
        throw "Apply the 0.1.21 audio update first; expected 0.1.21 or 0.1.22 in $relative. No files were changed."
    }
    if ([int]$match.Groups[1].Value -lt 22) {
        $text = [regex]::Replace($text, '(versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.)\d+"', '${1}22"')
        $codes = [regex]::Matches($text, '(?m)^([ \t]*versionCode\s*=\s*)(\d+)([ \t]*\r?)$')
        if ($codes.Count -ne 1) { throw "Ambiguous versionCode in $relative. No files were changed." }
        $code = $codes[0]
        $minimum = if ($module -eq 'automotive') { 1206 } else { 42 }
        $line = $code.Groups[1].Value + [Math]::Max([int]$code.Groups[2].Value + 1, $minimum) + $code.Groups[3].Value
        $text = $text.Remove($code.Index, $code.Length).Insert($code.Index, $line)
        Add-Text $relative $text
    }
}
foreach ($relative in @(
    'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahScreenGeometry.kt',
    'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahFullscreenContent.kt',
    'common/src/test/java/com/shilapi/xcertplay/voyah/VoyahScreenGeometryTest.kt'
)) {
    $source = Join-Path (Join-Path $PSScriptRoot 'payload') $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing payload: $relative" }
    $changes[$relative] = [System.IO.File]::ReadAllBytes($source)
}
$readme = Read-Source 'README.md'
if (-not $readme.Contains('<!-- voyah-layout-0.1.22 -->')) {
    $nl = if ($readme.Contains("`r`n")) { "`r`n" } else { "`n" }
    $notes = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CHANGELOG-0.1.22.md'), [System.Text.Encoding]::UTF8)
    Add-Text 'README.md' ($readme.TrimEnd() + $nl + $nl + $notes.Trim() + $nl)
}

# Validate all source locations first, then keep exact copies for rollback.
$backup = Join-Path $ProjectRoot ('build/voyah-layout-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
$written = New-Object 'System.Collections.Generic.List[string]'
$created = New-Object 'System.Collections.Generic.List[string]'
try {
    foreach ($entry in $changes.GetEnumerator()) {
        $target = Join-Path $ProjectRoot $entry.Key
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            $old = [System.IO.File]::ReadAllBytes($target)
            if ([Convert]::ToBase64String($old) -eq [Convert]::ToBase64String($entry.Value)) { continue }
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
if ($written.Count -eq 0) { Write-Host 'Layout update is already applied.' }
else { Write-Host ('Source backup: ' + $backup) }
Write-Host 'Ready. Build automotive and install APK. Root-helper installation is not needed for this UI change.'
