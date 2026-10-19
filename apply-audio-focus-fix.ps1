# Source-only update. Does not connect to the vehicle.
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

$hostPath = 'common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt'
$hostSource = Read-Source $hostPath
$nl = if ($hostSource.Contains("`r`n")) { "`r`n" } else { "`n" }
$argument = '            audioFocusAutoYield = com.shilapi.xcertplay.voyah.VoyahAudioFocusSettings.autoYield(this),'
$argumentPattern = '(?m)^[ \t]*audioFocusAutoYield\s*=\s*[^\r\n,]+,[^\r\n]*'
$arguments = [regex]::Matches($hostSource, $argumentPattern)
if ($arguments.Count -eq 1) {
    $hostSource = [regex]::Replace($hostSource, $argumentPattern, $argument)
} elseif ($arguments.Count -eq 0) {
    $anchor = '            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),'
    if ([regex]::Matches($hostSource, [regex]::Escape($anchor)).Count -ne 1) {
        throw 'Cannot identify the media sink factory. No files were changed.'
    }
    $hostSource = $hostSource.Replace($anchor, $anchor + $nl + $argument)
} else { throw 'Several media sink arguments found. No files were changed.' }
Add-Text $hostPath $hostSource

$sinkPath = 'shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt'
$sinkSource = Read-Source $sinkPath
foreach ($name in @('audioFocusAutoYield', 'muteMediaOnTransientLoss')) {
    $pattern = '(private val ' + $name + ': Boolean = )(true|false)(,)'
    if ([regex]::Matches($sinkSource, $pattern).Count -ne 1) {
        throw "Cannot identify the $name default. No files were changed."
    }
    $sinkSource = [regex]::Replace($sinkSource, $pattern, '${1}false${3}')
}
if (-not $sinkSource.Contains('Audio: focus policy enabled=')) {
    $sinkNl = if ($sinkSource.Contains("`r`n")) { "`r`n" } else { "`n" }
    $anchor = '    private var currentListener = listenerFor(focusGeneration)'
    if ([regex]::Matches($sinkSource, [regex]::Escape($anchor)).Count -ne 1) {
        throw 'Cannot identify the audio-focus coordinator. No files were changed.'
    }
    $log = @'
    init {
        runCatching { report("Audio: focus policy enabled=$enabled muteOnTransientLoss=$muteMediaOnTransientLoss") }
    }
'@
    $log = $log.Replace("`r`n", "`n").Replace("`n", $sinkNl)
    $sinkSource = $sinkSource.Replace($anchor, $anchor + $sinkNl + $log)
}
Add-Text $sinkPath $sinkSource

foreach ($module in @('automotive', 'mobile')) {
    $relative = "$module/build.gradle.kts"
    $text = Read-Source $relative
    $match = [regex]::Match($text, 'versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.(\d+)"')
    if (-not $match.Success -or [int]$match.Groups[1].Value -gt 21) {
        throw "Unexpected version in $relative. No files were changed."
    }
    if ([int]$match.Groups[1].Value -lt 21) {
        $text = [regex]::Replace($text, '(versionName\s*=\s*"[^"\r\n]*voyahplay\.0\.1\.)\d+"', '${1}21"')
        $codes = [regex]::Matches($text, '(?m)^([ \t]*versionCode\s*=\s*)(\d+)([ \t]*\r?)$')
        if ($codes.Count -ne 1) { throw "Ambiguous versionCode in $relative. No files were changed." }
        $code = $codes[0]
        $minimum = if ($module -eq 'automotive') { 1205 } else { 41 }
        $number = [Math]::Max([int]$code.Groups[2].Value + 1, $minimum)
        $line = $code.Groups[1].Value + $number + $code.Groups[3].Value
        $text = $text.Remove($code.Index, $code.Length).Insert($code.Index, $line)
        Add-Text $relative $text
    }
}

foreach ($relative in @(
    'common/src/main/java/com/shilapi/xcertplay/voyah/VoyahAudioFocusSettings.kt',
    'common/src/test/java/com/shilapi/xcertplay/voyah/VoyahAudioFocusSettingsTest.kt',
    'shared/src/test/java/com/shilapi/xcertplay/media/VoyahTransientFocusTest.kt'
)) {
    $source = Join-Path (Join-Path $PSScriptRoot 'payload') $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing payload: $relative" }
    $changes[$relative] = [System.IO.File]::ReadAllBytes($source)
}
$readme = Read-Source 'README.md'
if (-not $readme.Contains('<!-- voyah-audio-focus-0.1.21 -->')) {
    $notes = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CHANGELOG-0.1.21.md'), [System.Text.Encoding]::UTF8)
    Add-Text 'README.md' ($readme.TrimEnd() + $nl + $nl + $notes.Trim() + $nl)
}

# All edits are validated before writing. A failed write restores the saved files.
$backup = Join-Path $ProjectRoot ('build/voyah-audio-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
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
if ($written.Count -eq 0) { Write-Host 'Audio fix is already applied.' }
else { Write-Host ('Source backup: ' + $backup) }
Write-Host 'Ready. Build automotive, install APK and reconnect CarPlay to create a new audio session.'
