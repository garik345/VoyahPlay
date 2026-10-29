# Windows PowerShell 5.1+. Read-only evidence for unknown Voyah firmware.
[CmdletBinding()]
param([string]$Adb = 'adb', [string]$Serial, [string]$OutputDirectory, [ValidateRange(0,60)][int]$TraceSeconds = 20)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @()
$script:Report = $null
$script:AdbCode = 0
$script:Root = $false
$script:UseSu = $false
$script:CapturedBytes = 0L
$script:Files = [Collections.Generic.List[object]]::new()
$script:Missing = [Collections.Generic.List[string]]::new()
$script:Packages = [Collections.Generic.List[object]]::new()
$script:Seen = @{}
$utf8 = [Text.UTF8Encoding]::new($false)

function Redact([string]$Text) {
    $Text = [regex]::Replace($Text, '(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b', '<MAC>')
    $Text = [regex]::Replace($Text, '\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?::[0-9]+)?\b', '<IP>')
    return [regex]::Replace($Text, '(?im)^.*\b(?:ssid|bssid|imei|imsi|iccid|vin|latitude|longitude|password|passwd|access_token)\s*[:=].*$', '<personal field omitted>')
}
function Append-Report([string]$Text) {
    if ($script:Report) { [IO.File]::AppendAllText($script:Report, (Redact $Text) + "`n", $utf8) }
}
function New-AdbProcess([string[]]$Arguments) {
    # No local paths enter Arguments; remote paths and serials are constrained ASCII.
    foreach ($argument in @($script:Target) + @($Arguments)) {
        if ($argument -notmatch '^[A-Za-z0-9_./~+=:@%*?,\[\]-]+$') { throw 'Unsafe ADB argument rejected.' }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo.FileName = $script:AdbExe
    $process.StartInfo.Arguments = (@($script:Target) + @($Arguments)) -join ' '
    $process.StartInfo.UseShellExecute = $false
    $process.StartInfo.CreateNoWindow = $true
    $process.StartInfo.RedirectStandardOutput = $true
    $process.StartInfo.RedirectStandardError = $true
    return $process
}
function Read-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 20, [switch]$QuietReport) {
    $process = New-AdbProcess $Arguments
    $result = ''
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) { throw 'ADB read timeout.' }
        $result = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        $script:AdbCode = $process.ExitCode
        if ($errorText) { $result += "`n" + $errorText }
    } catch { $script:AdbCode = -1; $result = $_.Exception.Message }
    finally {
        try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch {}
        $process.Dispose()
    }
    if (-not $QuietReport) {
        $bounded = if ($result.Length -gt 12000) { $result.Substring(0,12000) + "`n<text truncated>" } else { $result }
        Append-Report ("ADB $($Arguments -join ' ')`n$bounded`nEXIT=$script:AdbCode")
    }
    return $result
}
function Remote-Read([string[]]$Arguments) {
    $prefix = if ($script:UseSu) { @('shell','su','0') } else { @('shell') }
    return Read-Adb (@($prefix) + @($Arguments))
}
function Save-Text([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $script:Capture $Name), (Redact $Text), $utf8)
}
function Sha256([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $digest.Dispose(); $stream.Dispose() }
}
function Save-RemoteBinary([string]$Remote, [string]$Name, [string]$Group, [long]$Limit, [string]$KnownHash = '') {
    if ($script:Seen.ContainsKey($Remote)) { return }
    $script:Seen[$Remote] = $true
    Write-Host ('Чтение: ' + $Remote)
    $record = [ordered]@{ group=$Group; remote=$Remote; file=$Name; captured=$false; bytes=0L; sha256=''; reason='' }
    try {
        $digest = Remote-Read @('sha256sum',$Remote)
        if ($script:AdbCode -ne 0 -or $digest -notmatch '(?m)^([a-fA-F0-9]{64})\s+') { throw 'File unavailable or SHA-256 cannot be read.' }
        $record.sha256 = $Matches[1].ToLowerInvariant()
        [void](Remote-Read @('ls','-ld',$Remote))
        [void](Remote-Read @('readlink','-f',$Remote))
        if ($KnownHash -and $record.sha256 -eq $KnownHash) { $record.reason='matches reviewed injector; bytes already available'; return }
        $size = Remote-Read @('stat','-Lc','%s',$Remote)
        $length = 0L
        if ($script:AdbCode -ne 0 -or -not [long]::TryParse($size.Trim(),[ref]$length) -or $length -le 0 -or $length -gt $Limit) { throw 'Invalid or excessive file size.' }
        if ($script:CapturedBytes + $length -gt 1536MB) { throw 'Capture size budget exceeded.' }
        $record.bytes = $length
        $destination = [IO.Path]::GetFullPath((Join-Path $script:Capture $Name))
        if (-not $destination.StartsWith($script:Capture + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid local destination.' }
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination))
        $partial = $destination + '.partial'
        $command = if ($script:UseSu) { @('exec-out','su','0','cat',$Remote) } else { @('exec-out','cat',$Remote) }
        $process = New-AdbProcess $command
        $output = $null
        try {
            [void]$process.Start()
            $errors = $process.StandardError.ReadToEndAsync()
            $output = [IO.File]::Open($partial,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            $buffer = [byte[]]::new(65536)
            $clock = [Diagnostics.Stopwatch]::StartNew()
            $received = 0L
            while ($true) {
                $remaining = 180000 - [int]$clock.ElapsedMilliseconds
                if ($remaining -le 0) { throw 'ADB binary read timeout.' }
                $read = $process.StandardOutput.BaseStream.ReadAsync($buffer,0,$buffer.Length)
                if (-not $read.Wait($remaining)) { throw 'ADB binary read timeout.' }
                $count = $read.GetAwaiter().GetResult()
                if ($count -eq 0) { break }
                $received += $count
                if ($received -gt $length) { throw 'Binary output exceeds declared size.' }
                $output.Write($buffer,0,$count)
            }
            $output.Dispose(); $output=$null
            if (-not $process.WaitForExit(5000)) { throw 'ADB exit timeout.' }
            $errorText = $errors.GetAwaiter().GetResult()
            if ($process.ExitCode -ne 0) { throw ('ADB binary read failed: ' + $errorText) }
            if ($received -ne $length -or (Sha256 $partial) -ne $record.sha256) { throw 'Binary length/SHA-256 mismatch.' }
            [IO.File]::Move($partial,$destination)
            $script:CapturedBytes += $length
            $record.captured = $true
            Append-Report ("BINARY_VERIFIED=$Name bytes=$length sha256=$($record.sha256)")
        } finally {
            try { if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(5000) } } catch {}
            if ($output) { $output.Dispose() }
            $process.Dispose()
            if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
        }
    } catch {
        $record.reason = $_.Exception.Message
        $script:Missing.Add($Remote + ': ' + $record.reason)
        Append-Report ('FILE_UNAVAILABLE=' + $Remote + ' ' + $record.reason)
    } finally { $script:Files.Add([PSCustomObject]$record) }
}
function Window-Snapshot {
    $raw = Read-Adb @('shell','dumpsys','window','windows') -QuietReport
    $code = $script:AdbCode
    $filtered = @($raw -split "`r?`n" | Where-Object {
        $_ -match '(?i)mCurrentFocus|mFocusedApp|mTopFullscreen|mResumedActivity|mObscuringWindow|mHoldScreenWindow|mShowingDream|mScreenOn|mAppBounds|mFrame=|mWindowFrames:|mViewVisibility=|mHasSurface=|isOnScreen=|isVisible=|mPolicyVisibility|mForceHide|Window #[0-9]+|com\.shilapi\.xcertplay|navigation.?bar|status.?bar|dock.?bar'
    } | Select-Object -First 1200)
    return "EXIT=$code`n" + ($filtered -join "`n")
}

$deviceText = Read-Adb @('devices','-l')
if ($script:AdbCode -ne 0) { throw 'ADB devices failed.' }
$devices = @($deviceText -split "`r?`n" | ForEach-Object {
    if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)') { [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2]} }
})
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Select exactly one vehicle with -Serial.' }
    $Serial=$devices[0].Serial
}
if ($Serial -notmatch '^[A-Za-z0-9._:\[\]-]+$' -or @($devices | Where-Object {$_.Serial -eq $Serial -and $_.State -eq 'device'}).Count -ne 1) { throw 'Selected vehicle is absent, offline or unauthorized.' }
$script:Target=@('-s',$Serial)
if (-not $OutputDirectory) { $OutputDirectory=[Environment]::GetFolderPath('Desktop') }
$captureName='Voyah-compatibility-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8)
$script:Capture=[IO.Path]::GetFullPath((Join-Path $OutputDirectory $captureName))
if (Test-Path -LiteralPath $script:Capture) { throw 'Capture already exists.' }
[void][IO.Directory]::CreateDirectory($script:Capture)
$script:Report=Join-Path $script:Capture 'report.txt'
Append-Report "VoyahPlay compatibility collector 1.0`nREAD ONLY`nDEVICE=$Serial"
$identity=Read-Adb @('shell','su','0','id') -TimeoutSeconds 8
$script:Root=$script:AdbCode -eq 0 -and $identity -match '(?m)^uid=0(?:\(|\s|$)'
$script:UseSu=$script:Root
if (-not $script:Root) {
    $directIdentity=Read-Adb @('shell','id') -TimeoutSeconds 8
    $script:Root=$script:AdbCode -eq 0 -and $directIdentity -match '(?m)^uid=0(?:\(|\s|$)'
}
Append-Report ('ROOT_READ_ACCESS=' + [int]$script:Root)
Append-Report ('ROOT_TRANSPORT=' + $(if ($script:UseSu) {'su-0'} elseif ($script:Root) {'adbd-root'} else {'shell'}))
if (-not $script:Root) { Write-Host 'Root недоступен: сохраню доступные файлы и отмечу ограничения в отчёте.' -ForegroundColor Yellow }
foreach ($property in @('ro.build.fingerprint','ro.build.display.id','ro.build.version.sdk','ro.build.version.release','ro.product.model','ro.product.device','ro.product.manufacturer','ro.build.version.incremental')) { [void](Read-Adb @('shell','getprop',$property)) }
[void](Read-Adb @('shell','wm','size')); [void](Read-Adb @('shell','wm','density'))
$before=Window-Snapshot
Save-Text 'windows-before.txt' $before
if ($TraceSeconds -gt 0) {
    Write-Host ("Запись всплывающих окон: $TraceSeconds секунд. Сейчас измените громкость и откройте штатный климат, если проверяете вспышку дока.") -ForegroundColor Cyan
    $trace=[Collections.Generic.List[string]]::new()
    $traceClock=[Diagnostics.Stopwatch]::StartNew()
    while ($traceClock.Elapsed.TotalSeconds -lt $TraceSeconds) {
        $trace.Add(('ELAPSED_MS=' + $traceClock.ElapsedMilliseconds + "`n" + (Window-Snapshot)))
        Start-Sleep -Milliseconds 1000
    }
    Save-Text 'windows-trace.txt' ($trace -join "`n`n")
}
Save-Text 'windows-after.txt' (Window-Snapshot)
$traceLog=Read-Adb @('shell','logcat','-d','-v','threadtime','-t','4000','-s','VoyahDockVisibility:V','VoyahTweaksFullscreen:V','*:S')
Save-Text 'window-events.txt' $traceLog
Write-Host 'Снимки окон сохранены. Собираю файлы прошивки; дождитесь готового ZIP.'

# Only public OEM packages matching these functions; no user-installed application inventory is saved.
$inventory=Read-Adb @('shell','pm','list','packages') -QuietReport
$wanted=@('com.qinggan.canbus.service','com.qinggan.carsignal.service','com.qinggan.app.launcher','com.qinggan.systemui','com.qinggan.QGBus','com.qinggan.cluster','com.qinggan.app.vehicle','com.pateo.rdsapp','com.shilapi.xcertplay')
$extra=@($inventory -split "`r?`n" | ForEach-Object {
    if ($_ -match '^package:((?:com\.(?:qinggan|pateo|lantu|dongfeng))\.[A-Za-z0-9_.]+)$') {
        $name=$Matches[1]
        if ($name -match '(?i)canbus|carsignal|launcher|systemui|cluster|qgbus|windowpolicy|climate|aircondition|hvac|rdsapp|vehicle|volume') { $name }
    }
})
$candidatePackages=@(@($wanted)+@($extra) | Sort-Object -Unique | Select-Object -First 32)
foreach ($package in $candidatePackages) {
    $paths=Read-Adb @('shell','pm','path',$package)
    $validPaths=@($paths -split "`r?`n" | ForEach-Object {
        if ($_ -match '^package:(/(?:system|system_ext|vendor|product|odm|oem|data/app)/[A-Za-z0-9_./~+=@-]+\.apk)$') {
            $remote=$Matches[1]
            if (@($remote -split '/' | Where-Object {$_ -eq '..' -or $_ -eq '.'}).Count -eq 0) { $remote }
        }
    } | Select-Object -First 16)
    if ($validPaths.Count -eq 0) { $script:Missing.Add('package:' + $package + ' (not installed or invalid paths)'); continue }
    $details=Read-Adb @('shell','dumpsys','package',$package) -QuietReport
    $version=@($details -split "`r?`n" | Where-Object {$_ -match '^\s*(versionCode=|versionName=|codePath=|userId=|pkgFlags=|privateFlags=)|android\.permission\.WRITE_CANBUS|WRITE_CANBUS: granted='} | Select-Object -First 40) -join "`n"
    Append-Report ("PACKAGE=$package`n$version")
    $script:Packages.Add([PSCustomObject]@{package=$package;paths=@($validPaths);version=$version})
    if ($package -eq 'com.shilapi.xcertplay') {
        foreach ($remote in $validPaths) { [void](Remote-Read @('sha256sum',$remote)) }
        continue
    }
    $index=0
    foreach ($remote in $validPaths) {
        $limit=if ($package -match '(?i)vehicle') {512MB} elseif ($package -match '(?i)launcher') {192MB} else {128MB}
        Save-RemoteBinary $remote ('apks/' + $package + '-' + $index + '.apk') 'OEM APK' $limit
        $index++
    }
}
foreach ($file in @('QGVehicle.jar','QGAPI.jar','QGMOD.jar','ActivityExt.jar','car-frameworks-service.jar','services.jar','framework.jar')) {
    foreach ($partition in @('system','system_ext','vendor','product')) {
        $name=if ($partition -eq 'system') {$file} else {$partition+'-'+$file}
        Save-RemoteBinary ('/'+$partition+'/framework/'+$file) ('framework/'+$name) 'SDK/framework' (128MB)
        if ($script:Files[$script:Files.Count-1].captured) { break }
    }
}
foreach ($file in @('ss11.bin','ss9.bin','load.bin')) { Save-RemoteBinary ('/data/local/tmp/'+$file) ('tweaks/'+$file) 'Tweaks' (4MB) }
Save-RemoteBinary '/data/local/tmp/frida-inject' 'tweaks/frida-inject' 'Tweaks' (64MB) '0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa'
Save-RemoteBinary '/system/etc/init.logcat.sh' 'boot/init.logcat.sh' 'Boot' (128KB)
foreach ($file in @('owner','allowed.uid','runtime.sh','boot-report.txt','boot-hook.original','autostart.conf','radio-labels.conf')) {
    Save-RemoteBinary ('/data/local/voyahplay-root-helper/'+$file) ('helper/'+$file) 'Helper' (128KB)
}
foreach ($key in @('voyahplay_tweaks_bounds_probe','voyahplay_top_bounds_probe','voyahplay_status_bar_probe','voyahplay_tweaks_bounds_test','voyahplay_top_bounds_lease')) { [void](Read-Adb @('shell','settings','get','global',$key)) }
$windowPrefs=Read-Adb @('shell','run-as','com.shilapi.xcertplay','cat','shared_prefs/voyah_tweaks_fullscreen.xml')
Save-Text 'voyahplay-window-settings.txt' $windowPrefs
$log=Read-Adb @('shell','logcat','-d','-v','threadtime','-t','2000','-s','VoyahDockVisibility:V','VoyahTweaksFullscreen:V','VoyahClient:V','VoyahControls:V','VoyahHvac:V','*:S')
Save-Text 'voyahplay-logcat.txt' $log
$helperLog=Remote-Read @('tail','-n','200','/data/local/voyahplay-root-helper/server.log')
Save-Text 'helper-log.txt' $helperLog
$summary=[PSCustomObject]@{collector='1.0';readOnly=$true;root=$script:Root;useSu=$script:UseSu;traceSeconds=$TraceSeconds;capturedBytes=$script:CapturedBytes;packages=@($script:Packages.ToArray());files=@($script:Files.ToArray());unavailable=@($script:Missing.ToArray())}
[IO.File]::WriteAllText((Join-Path $script:Capture 'summary.json'),(ConvertTo-Json -InputObject $summary -Depth 7),$utf8)
Append-Report ("`nCAPTURED_BYTES=$script:CapturedBytes`nUNAVAILABLE_COUNT=$($script:Missing.Count)`n" + ($script:Missing -join "`n"))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=$script:Capture + '.zip'
[IO.Compression.ZipFile]::CreateFromDirectory($script:Capture,$zip,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Host ('Готовый архив: ' + $zip) -ForegroundColor Green
[PSCustomObject]@{Zip=$zip;Missing=@($script:Missing.ToArray());Root=$script:Root}
