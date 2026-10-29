# PowerShell 5.1+. Read-only firmware capture. Binary ADB output is copied as bytes.
[CmdletBinding()]
param([string]$Adb = 'adb', [string]$Serial, [string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference = $false }
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @()
$script:Report = $null
$script:AdbCode = 0
$utf8 = [Text.UTF8Encoding]::new($false)

function Read-Adb([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $script:AdbCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    $text = $lines -join "`n"
    if ($script:Report) { [IO.File]::AppendAllText($script:Report, "ADB $($Arguments -join ' ')`n$text`nEXIT=$script:AdbCode`n", $utf8) }
    return $text
}

$deviceLines = Read-Adb -Arguments @('devices','-l')
if ($script:AdbCode -ne 0) { throw 'ADB devices failed.' }
$devices = @($deviceLines -split "`r?`n" | ForEach-Object {
    if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)') { [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2]} }
})
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Select one connected vehicle with -Serial.' }
    $Serial = $devices[0].Serial
}
if ($Serial -match '[\s"]') { throw 'Invalid ADB serial.' }
if (@($devices | Where-Object { $_.Serial -eq $Serial -and $_.State -eq 'device' }).Count -ne 1) {
    throw 'Selected device is absent, offline or unauthorized.'
}
$script:Target = @('-s',$Serial)
$identity = Read-Adb -Arguments @('shell','su','0','id')
if ($script:AdbCode -ne 0 -or $identity -notmatch '(?m)^uid=0(?:\(|\s|$)') { throw 'Root read access (su 0) is required for this capture.' }
if (-not $OutputDirectory) { $OutputDirectory = [Environment]::GetFolderPath('Desktop') }
$captureName = 'Voyah-fullscreen-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$capture = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $captureName
if (Test-Path -LiteralPath $capture) { throw 'Capture already exists.' }
[void][IO.Directory]::CreateDirectory($capture)
$script:Report = Join-Path $capture 'report.txt'
[IO.File]::WriteAllText($script:Report, "VoyahPlay fullscreen firmware evidence`nREAD ONLY`nDEVICE=$Serial`n$identity`n", $utf8)
$missing = [Collections.Generic.List[string]]::new()
$hashes = [Collections.Generic.List[object]]::new()

function Save-RemoteBinary([string]$Remote, [string]$Name, [long]$Limit, [string]$KnownHash = '') {
    Write-Host ('Чтение: ' + $Remote)
    $digest = Read-Adb -Arguments @('shell','su','0','sha256sum',$Remote)
    if ($script:AdbCode -ne 0 -or $digest -notmatch '(?m)^([a-fA-F0-9]{64})\s+') { $missing.Add($Remote); return }
    $expected = $Matches[1].ToLowerInvariant()
    if ($KnownHash -and $expected -eq $KnownHash) {
        $hashes.Add([PSCustomObject]@{remote=$Remote;sha256=$expected;captured=$false;reason='matches reviewed injector'})
        return
    }
    $size = Read-Adb -Arguments @('shell','su','0','stat','-c','%s',$Remote)
    $length = 0L
    if ($script:AdbCode -ne 0 -or -not [long]::TryParse($size.Trim(),[ref]$length) -or $length -le 0 -or $length -gt $Limit) {
        $missing.Add($Remote + ' (invalid/oversized file)'); return
    }
    $destination = [IO.Path]::GetFullPath((Join-Path $capture $Name))
    if (-not $destination.StartsWith($capture + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid destination.' }
    $partial = $destination + '.partial'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $script:AdbExe
    # Serial contains no whitespace/quotes; all remote paths are fixed below, not shell input.
    $process.StartInfo.Arguments = (@('-s',$Serial,'exec-out','su','0','cat',$Remote) -join ' ')
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    $output = $null
    $done = $false
    try {
        [void]$process.Start()
        $errors = $process.StandardError.ReadToEndAsync()
        $output = [IO.File]::Open($partial,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $copy = $process.StandardOutput.BaseStream.CopyToAsync($output)
        if (-not $copy.Wait(60000) -or -not $process.WaitForExit(5000)) { throw 'ADB binary read timeout.' }
        [void]$copy.GetAwaiter().GetResult()
        $output.Dispose(); $output = $null
        $errorText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw ('ADB binary read failed: ' + $errorText) }
        if ((Get-Item -LiteralPath $partial).Length -ne $length) { throw 'Binary length mismatch.' }
        $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw 'Binary SHA256 mismatch.' }
        [IO.File]::Move($partial,$destination)
        $hashes.Add([PSCustomObject]@{file=$Name;remote=$Remote;bytes=$length;sha256=$actual;captured=$true})
        $done = $true
        [IO.File]::AppendAllText($script:Report, "BINARY_VERIFIED=$Name bytes=$length sha256=$actual`n", $utf8)
    } catch {
        $missing.Add($Remote + ': ' + $_.Exception.Message)
        [IO.File]::AppendAllText($script:Report, "BINARY_ERROR=$Remote $($_.Exception.Message)`n", $utf8)
    } finally {
        try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch {}
        if ($output) { $output.Dispose() }
        $process.Dispose()
        if (-not $done -and (Test-Path -LiteralPath $partial)) { Remove-Item -LiteralPath $partial -Force }
    }
}

foreach ($property in @('ro.build.fingerprint','ro.build.display.id','ro.build.version.sdk','ro.product.model')) {
    [void](Read-Adb -Arguments @('shell','getprop',$property))
}
[void](Read-Adb -Arguments @('shell','wm','size'))
[void](Read-Adb -Arguments @('shell','wm','density'))
[void](Read-Adb -Arguments @('shell','su','0','sha256sum','/system/etc/init.logcat.sh'))
foreach ($key in @('voyahplay_tweaks_bounds_probe','voyahplay_top_bounds_probe','voyahplay_status_bar_probe')) {
    [void](Read-Adb -Arguments @('shell','settings','get','global',$key))
}
Save-RemoteBinary '/system/framework/services.jar' 'services.jar' (64MB)
Save-RemoteBinary '/data/local/tmp/ss11.bin' 'ss11.bin' (4MB)
Save-RemoteBinary '/system/priv-app/SystemUI-release-signed/SystemUI-release-signed.apk' 'SystemUI.apk' (64MB)
Save-RemoteBinary '/data/local/tmp/frida-inject' 'frida-inject' (64MB) '0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa'
$logcat = Read-Adb -Arguments @('shell','logcat','-d','-v','threadtime','-t','1000','-s','VoyahTweaksFullscreen:V','*:S')
[IO.File]::WriteAllText((Join-Path $capture 'window-logcat.txt'),$logcat,$utf8)
$settings = Read-Adb -Arguments @('shell','run-as','com.shilapi.xcertplay','cat','shared_prefs/voyah_tweaks_fullscreen.xml')
[IO.File]::WriteAllText((Join-Path $capture 'window-settings.txt'),$settings,$utf8)
[IO.File]::WriteAllText((Join-Path $capture 'SHA256.json'),(ConvertTo-Json -InputObject @($hashes.ToArray()) -Depth 4),$utf8)
[IO.File]::AppendAllText($script:Report,"`nMISSING=$($missing -join '; ')`n",$utf8)
$zip = $capture + '.zip'
Compress-Archive -LiteralPath $capture -DestinationPath $zip
Write-Host ('Архив: ' + $zip)
[PSCustomObject]@{Zip=$zip;Missing=@($missing.ToArray())}
