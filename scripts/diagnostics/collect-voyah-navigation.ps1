# PowerShell 5.1+. Read navigation evidence and OEM interfaces; no device settings are changed.
[CmdletBinding()]
param([string]$Adb, [string]$Serial, [string]$OutputDirectory)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
if (-not $Adb) {
    $candidate = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($candidate) { $Adb = $candidate.Source }
    else {
        foreach ($path in @((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'), 'C:\Tools\platform-tools\adb.exe')) {
            if (Test-Path -LiteralPath $path -PathType Leaf) { $Adb = $path; break }
        }
    }
}
if (-not $Adb) { throw 'ADB is not installed or was not found. Install Android Platform-Tools, then use -Adb with the full path to adb.exe.' }
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @()
$script:Report = $null
$script:AdbCode = 0
$utf8 = [Text.UTF8Encoding]::new($false)

function Invoke-NavigationRead {
    param([string[]]$Arguments)
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $script:AdbCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($script:Report) {
        [IO.File]::AppendAllText($script:Report,
            ('ADB ' + ($Arguments -join ' ') + "`n" + ($lines -join "`n") + "`nEXIT=$script:AdbCode`n"), $utf8)
    }
    return $lines
}

$deviceLines = @(Invoke-NavigationRead -Arguments @('devices', '-l'))
if ($script:AdbCode -ne 0) { throw 'ADB devices failed.' }
$devices = @($deviceLines | ForEach-Object {
    if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)') {
        [PSCustomObject]@{ Serial = $Matches[1]; State = $Matches[2] }
    }
})
if (-not $Serial) {
    if ($devices.Count -gt 1) { throw ('More than one ADB device. Run again with -Serial. Devices: ' + (($devices | ForEach-Object { $_.Serial }) -join ', ')) }
    if ($devices.Count -ne 1) { throw 'No ADB device. Connect the intended car and enable ADB.' }
    $Serial = $devices[0].Serial
}
$selected = @($devices | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' })
if ($selected.Count -ne 1) { throw 'The selected device is absent, offline or unauthorized.' }
$script:Target = @('-s', $Serial)

if (-not $OutputDirectory) { $OutputDirectory = [Environment]::GetFolderPath('Desktop') }
$captureName = 'Voyah-PI-navigation-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$capture = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $captureName
if (Test-Path -LiteralPath $capture) { throw 'Output already exists; no files were overwritten.' }
[void][IO.Directory]::CreateDirectory($capture)
$script:Report = Join-Path $capture 'report.txt'
[IO.File]::WriteAllText($script:Report, "Voyah navigation evidence`nREAD ONLY`nDEVICE=$Serial`n", $utf8)
$deviceLines | ForEach-Object { [IO.File]::AppendAllText($script:Report, $_ + "`n", $utf8) }

foreach ($property in @('ro.build.fingerprint', 'ro.build.version.sdk', 'ro.product.model')) {
    [void](Invoke-NavigationRead -Arguments @('shell', 'getprop', $property))
}
$missing = [Collections.Generic.List[string]]::new()
$packagePaths = @(Invoke-NavigationRead -Arguments @('shell', 'pm', 'path', 'com.qinggan.cluster'))
$index = 0
foreach ($line in $packagePaths) {
    if ($line -match '^package:(/.+\.apk)$') {
        $remotePath = $Matches[1]
        $name = 'com.qinggan.cluster-' + $index + '.apk'
        [void](Invoke-NavigationRead -Arguments @('pull', $remotePath, (Join-Path $capture $name)))
        if ($script:AdbCode -ne 0) { $missing.Add($name) }
        $index++
    }
}
if ($index -eq 0) { $missing.Add('com.qinggan.cluster APK (package not found)') }
[void](Invoke-NavigationRead -Arguments @('pull', '/system/framework/QGAPI.jar', (Join-Path $capture 'QGAPI.jar')))
if ($script:AdbCode -ne 0) { $missing.Add('QGAPI.jar') }

$navigationLog = @(Invoke-NavigationRead -Arguments @('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'VoyahNavigation:V', '*:S'))
[IO.File]::WriteAllLines((Join-Path $capture 'VoyahNavigation-logcat.txt'), $navigationLog, $utf8)
$setting = @(Invoke-NavigationRead -Arguments @('shell', 'run-as', 'com.shilapi.xcertplay', 'cat', 'shared_prefs/voyah_navigation.xml'))
[IO.File]::WriteAllLines((Join-Path $capture 'navigation-setting.txt'), $setting, $utf8)
# run-as may be unavailable on a non-debug APK. Its error is diagnostic evidence, not an empty successful setting.

$hashes = @(Get-ChildItem -LiteralPath $capture -File | Where-Object { $_.Extension -in @('.apk', '.jar') } | ForEach-Object {
    $hash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    [PSCustomObject]@{ file = $_.Name; bytes = $_.Length; sha256 = $hash.Hash.ToLowerInvariant() }
})
[IO.File]::WriteAllText((Join-Path $capture 'SHA256.json'), (ConvertTo-Json -InputObject $hashes -Depth 4), $utf8)
[IO.File]::AppendAllText($script:Report, "`nMISSING=$($missing -join '; ')`n", $utf8)
$zip = $capture + '.zip'
Compress-Archive -LiteralPath $capture -DestinationPath $zip
Write-Host "Report ZIP: $zip"
if ($missing.Count -gt 0) { Write-Warning ('Partial capture: ' + ($missing -join ', ')) }
Write-Host 'No packages, permissions, settings or running services were changed.'
