# PowerShell 5.1+. Root helper 1.2.10 with optional OEM-radio labels.
[CmdletBinding()]
param(
    [ValidateSet('Install','Start','Status','Stop','EnableAutoStart','DisableAutoStart','RemoveAutoStart')][string]$Mode = 'Status',
    [string]$ProjectRoot,
    [string]$Serial,
    [string]$Adb = 'adb'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference = $false }
if (-not $ProjectRoot) {
    $directory = [System.IO.DirectoryInfo]::new($PSScriptRoot)
    while ($null -ne $directory) {
        if (Test-Path -LiteralPath (Join-Path $directory.FullName 'common/src/main/assets/voyah-helper/runtime.sh')) { $ProjectRoot = $directory.FullName; break }
        $directory = $directory.Parent
    }
}
if (-not $ProjectRoot) { throw 'Use -ProjectRoot with your VoyahPlay source directory.' }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$bootstrapPath = Join-Path $PSScriptRoot 'root-helper-bootstrap.sh'
if (-not (Test-Path -LiteralPath $bootstrapPath -PathType Leaf)) { throw 'root-helper-bootstrap.sh is missing next to this script.' }
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @(); if ($Serial) { $script:Target = @('-s', $Serial) }
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
$reportDirectory = Join-Path $ProjectRoot ('build/voyah-root-helper/' + $runName)
[void][System.IO.Directory]::CreateDirectory($reportDirectory)
$script:Report = Join-Path $reportDirectory 'report.txt'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($script:Report, "VoyahPlay root helper 1.2.10`nMode: $Mode`nTime: $(Get-Date -Format o)`n", $utf8)
function Log-Line([string]$Line) {
    Write-Host $Line
    [System.IO.File]::AppendAllText($script:Report, $Line + "`n", [System.Text.UTF8Encoding]::new($false))
}
function Invoke-HelperAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    Log-Line ('ADB ' + ($Arguments -join ' '))
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    foreach ($line in $lines) { Log-Line $line }
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB exit code $code; send $script:Report" }
    return ($lines -join "`n")
}
$remote = '/data/local/tmp/voyahplay-helper-' + [guid]::NewGuid().ToString('N') + '.sh'
$local = Join-Path $reportDirectory 'bootstrap.sh'
$pushed = $false
try {
    [void](Invoke-HelperAdb -Arguments @('get-state'))
    $identity = Invoke-HelperAdb -Arguments @('shell','su','0','id')
    if ($identity -notmatch '(?m)^uid=0(?:\(|\s|$)') { throw 'Root through ADB was not confirmed.' }
    $content = [System.IO.File]::ReadAllText($bootstrapPath).Replace("`r`n","`n").Replace("`r","`n")
    [System.IO.File]::WriteAllText($local, $content, $utf8)
    [void](Invoke-HelperAdb -Arguments @('push',$local,$remote)); $pushed = $true
    $result = Invoke-HelperAdb -Arguments @('shell','su','0','sh',$remote,$Mode.ToLowerInvariant())
    $expected = switch ($Mode) {
        'Stop' { 'HELPER_STOPPED=1' }
        'RemoveAutoStart' { 'AUTOSTART_REMOVED=1' }
        'EnableAutoStart' { 'AUTOSTART_ENABLED=1' }
        'DisableAutoStart' { 'AUTOSTART_ENABLED=0' }
        default { 'HELPER_READY=1' }
    }
    if (-not $result.Contains($expected)) { throw "Helper result not confirmed: expected $expected" }
    if ($Mode -in @('Install','Start','Status') -and (-not $result.Contains('CAPABILITIES=TWEAKS_BOUNDS_1_1') -or $result -notmatch '(?m)^HELPER_VERSION=1\.2\.10\r?$')) {
        throw 'Expected root helper 1.2.10. Build and install automotive from the same source update, then run -Mode Install.'
    }
    if ($Mode -eq 'Stop') { Log-Line 'DONE. Root helper stopped.' }
    elseif ($result -match '(?m)^REBOOT_REQUIRED=1\r?$' -or ($Mode -eq 'Install' -and $result -match '(?m)^BOOT_MOUNT_READ_ONLY=0\r?$')) {
        Log-Line 'DONE. Requested helper change completed. The system mount is still writable; reboot multimedia normally, then check helper status and BOOT_MOUNT_READ_ONLY=1.'
    }
    else { Log-Line 'DONE. Autostart is managed in APK settings. Full width uses the existing Tweaks bridge.' }
} catch { Log-Line ('ERROR: ' + $_.Exception.Message); throw }
finally {
    if ($pushed) {
        try { [void](Invoke-HelperAdb -Arguments @('shell','rm','-f',$remote) -AllowFailure) }
        catch { Log-Line ('Temporary cleanup failed: ' + $_.Exception.Message) }
    }
    if (Test-Path -LiteralPath $local) {
        try { Remove-Item -LiteralPath $local -Force }
        catch { Log-Line ('Temporary local cleanup failed: ' + $_.Exception.Message) }
    }
    Write-Host "Report: $script:Report"
}
