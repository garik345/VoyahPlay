# Read-only ADB capture. No window changes, scenario launches or injection.
[CmdletBinding()]
param([string]$Adb = 'adb', [string]$Label = 'VoyahPlay', [switch]$IncludeApks)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$utf8 = New-Object System.Text.UTF8Encoding($false)
$safeLabel = [regex]::Replace($Label, '[^A-Za-z0-9_-]', '_')
$base = Join-Path $PSScriptRoot 'build/voyah-status-bar'
$directory = Join-Path $base ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $safeLabel + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6))
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
& $Adb get-state
if ($LASTEXITCODE -ne 0) { throw 'ADB device is not ready.' }

function Capture([string]$name, [string[]]$ArgumentsArray) {
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Adb @ArgumentsArray 2>&1 | ForEach-Object { "$_" })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    $header = @(('ADB ' + ($ArgumentsArray -join ' ')), ('EXIT=' + $code))
    [System.IO.File]::WriteAllLines((Join-Path $directory $name), [string[]]($header + $output), $utf8)
    return $output
}

Capture 'window-windows.txt' @('shell','dumpsys','window','windows') | Out-Null
Capture 'window-displays.txt' @('shell','dumpsys','window','displays') | Out-Null
Capture 'window-policy.txt' @('shell','dumpsys','window','policy') | Out-Null
Capture 'activity.txt' @('shell','dumpsys','activity','activities') | Out-Null
Capture 'surface-names.txt' @('shell','dumpsys','SurfaceFlinger','--list') | Out-Null
Capture 'services.txt' @('shell','service','list') | Out-Null
Capture 'packages.txt' @('shell','pm','list','packages','-f') | Out-Null
Capture 'policy-control.txt' @('shell','settings','get','global','policy_control') | Out-Null
Capture 'build-fingerprint.txt' @('shell','getprop','ro.build.fingerprint') | Out-Null

foreach ($package in @('com.android.systemui','com.qinggan.systemui','com.qinggan.windowpolicy.service','com.qinggan.systemservice','com.qinggan.launcher','com.qinggan.launcher.oversea')) {
    $paths = @(Capture ($package + '-paths.txt') @('shell','pm','path',$package))
    if ($IncludeApks) {
        $number = 0
        foreach ($line in $paths) {
            if ($line -notmatch '^package:(/[^\r\n]+\.apk)$') { continue }
            $remote = $Matches[1]
            $number += 1
            $destination = Join-Path $directory ($package + '-' + $number + '.apk')
            $oldPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = 'Continue'
                $result = @(& $Adb pull $remote $destination 2>&1 | ForEach-Object { "$_" })
                $code = $LASTEXITCODE
            } finally { $ErrorActionPreference = $oldPreference }
            [System.IO.File]::WriteAllLines((Join-Path $directory ($package + '-' + $number + '-pull.txt')), [string[]](@('EXIT=' + $code) + $result), $utf8)
        }
    }
}
[System.IO.File]::WriteAllLines((Join-Path $directory 'capture.txt'), [string[]]@(
    ('LABEL=' + $Label), ('TIME=' + (Get-Date -Format 'o')),
    'This script only read diagnostics and optionally copied installed APK files.'
), $utf8)
$archive = $directory + '.zip'
Compress-Archive -Path (Join-Path $directory '*') -DestinationPath $archive
Write-Host ('Report ZIP: ' + $archive)
