# Windows PowerShell 5.1. Coordinates the existing, independently checked installers.
[CmdletBinding()]
param([ValidateSet('Install','Status','RestoreBridge')][string]$Mode='Install', [string]$Serial)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference=$false }
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$script:Target=@()
$script:Adb=Join-Path $PSScriptRoot 'helper\adb\adb.exe'
$powershell=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
$transcribing=$false
$exitCode=1
function Recovery-Sha([string]$Path) {
    $stream=[IO.File]::OpenRead($Path); $digest=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $digest.Dispose(); $stream.Dispose() }
}
function Recovery-Adb([string[]]$Arguments) {
    $saved=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $lines=@(& $script:Adb @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$saved }
    if($code -ne 0) { throw ('Ошибка ADB: '+($lines -join "`n")) }
    return ($lines -join "`n").Trim()
}
function Recovery-Stage([string]$File,[string[]]$Arguments) {
    $saved=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $lines=@(& $powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $File) @Arguments 2>&1 |
            ForEach-Object { $line=$_.ToString(); Write-Host $line; $line })
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$saved }
    if($code -ne 0) { throw ('Не завершён этап '+$File+' (код '+$code+'). Подробности выше и в журналах.') }
    return ($lines -join "`n")
}
function Recovery-Require([string]$Output,[string[]]$Markers) {
    foreach($marker in $Markers) {
        if($Output -notmatch ('(?m)^'+[regex]::Escape($marker)+'\r?$')) { throw ('Не подтверждено: '+$marker) }
    }
}
try {
    [void][IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot 'logs'))
    $log=Join-Path $PSScriptRoot ('logs\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)+'.txt')
    Start-Transcript -LiteralPath $log -Force | Out-Null
    $transcribing=$true
    Write-Host 'VoyahPlay RU — восстановление helper 1.2.16 и моста Tweaks 1.1' -ForegroundColor Cyan
    $manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'FILES-SHA256.json') -Raw | ConvertFrom-Json
    $names=@($manifest | ForEach-Object { $_.file })
    foreach($required in @('start-ru-recovery.ps1','INSTALL.cmd','STATUS.cmd','RESTORE_BRIDGE.cmd','README.txt',
        'helper/start-root-helper.ps1','helper/root-helper.ps1','helper/root-helper-bootstrap.sh','helper/helper-quiesce.sh',
        'helper/adb/adb.exe','helper/adb/AdbWinApi.dll','helper/adb/AdbWinUsbApi.dll','helper/FILES-SHA256.json',
        'tweaks-bridge/start-ru-bridge.ps1','tweaks-bridge/bridge-installer.sh','tweaks-bridge/bridge/ss11.bin',
        'tweaks-bridge/bridge/verification.json','tweaks-bridge/FILES-SHA256.json')) {
        if(@($names | Where-Object { $_ -ceq $required }).Count -ne 1) { throw ('Неполный комплект: '+$required) }
    }
    foreach($entry in $manifest) {
        if($entry.file -notmatch '^[A-Za-z0-9_./-]+$' -or $entry.file -match '(^|/)\.\.(/|$)' -or $entry.file.StartsWith('/') -or
            $entry.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Некорректный манифест комплекта.' }
        $actual=Recovery-Sha (Join-Path $PSScriptRoot $entry.file)
        if($actual -ne $entry.sha256) { throw ('Повреждён файл '+$entry.file+'. Распакуйте весь ZIP заново.') }
    }
    $devices=@((Recovery-Adb @('devices','-l')) -split "`r?`n" | ForEach-Object {
        if($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)(.*)$') {
            [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2];Details=$Matches[3].Trim()}
        }
    })
    if($devices.Count -eq 0) { throw 'Автомобиль не найден. Подключите ADB и повторите запуск.' }
    if($Serial) {
        $selected=@($devices | Where-Object { $_.Serial -eq $Serial })
        if($selected.Count -ne 1) { throw 'Указанный serial не найден.' }; $chosen=$selected[0]
    } elseif($devices.Count -eq 1) { $chosen=$devices[0] }
    else {
        for($i=0;$i -lt $devices.Count;$i++) { Write-Host ('{0}. {1} [{2}] {3}' -f ($i+1),$devices[$i].Serial,$devices[$i].State,$devices[$i].Details) }
        do {
            $answer=Read-Host 'Номер автомобиля (0 — выход)'
            if([string]::IsNullOrWhiteSpace($answer) -or $answer -eq '0') { throw [OperationCanceledException]::new('Отменено.') }
            $number=0
        } until([int]::TryParse($answer,[ref]$number) -and $number -ge 1 -and $number -le $devices.Count)
        $chosen=$devices[$number-1]
    }
    if($chosen.State -ne 'device') { throw ('Устройство '+$chosen.State+'. Подтвердите отладку или восстановите ADB.') }
    if($chosen.Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Неподдерживаемый формат serial.' }
    $script:Target=@('-s',$chosen.Serial)
    Write-Host ('Выбран автомобиль: '+$chosen.Serial)
    if((Recovery-Adb @('shell','su','0','id')) -notmatch '^uid=0(?:\(|\s|$)') { throw 'Нужен уже работающий root (su 0).' }
    if((Recovery-Adb @('shell','getprop','ro.build.version.sdk')) -ne '30') { throw 'Комплект предназначен для проверенной RU-прошивки Android 11.' }
    if($Mode -ne 'RestoreBridge' -and (Recovery-Adb @('shell','pm','path','--user','0','com.shilapi.xcertplay')) -notmatch '(?m)^package:.+\.apk\r?$') {
        throw 'Сначала установите актуальный APK VoyahPlay с helper 1.2.16, затем повторите INSTALL.cmd.'
    }
    if((Recovery-Adb @('shell','pm','list','packages','ru.kachalin.voyahtweaks')) -notmatch '(?m)^package:ru\.kachalin\.voyahtweaks\r?$') {
        throw 'Сначала установите, активируйте и выполните штатную подготовку системы в совместимых Tweaks.'
    }
    $serialArgs=@('-Serial',$chosen.Serial)
    if($Mode -eq 'Install') {
        Write-Host 'Этап 1/3: проверка RU-прошивки и установка моста Tweaks.'
        $bridge=Recovery-Stage 'tweaks-bridge\start-ru-bridge.ps1' (@('-Mode','Install')+$serialArgs)
        if($bridge -notmatch '(?m)^(RU_BRIDGE_INSTALLED|ALREADY_INSTALLED)=1\r?$') { throw 'Запись моста не подтверждена.' }
        Write-Host 'Этап 2/3: установка root helper из APK на этом автомобиле.'
        $helper=Recovery-Stage 'helper\start-root-helper.ps1' (@('-Mode','Install')+$serialArgs)
        Recovery-Require $helper @('HELPER_READY=1','HELPER_VERSION=1.2.16','HELPER_UID_MATCH=1')
        Write-Host 'Этап 3/3: восстановление автозапуска root helper.'
        $autostart=Recovery-Stage 'helper\root-helper.ps1' (@('-Mode','EnableAutoStart','-ProjectRoot',(Join-Path $PSScriptRoot 'helper'),'-Adb',$script:Adb)+$serialArgs)
        Recovery-Require $autostart @('AUTOSTART_ENABLED=1')
        Write-Host 'INSTALL_WRITTEN=1'
        Write-Host 'REBOOT_REQUIRED=1'
        Write-Host 'Компоненты установлены. Перезагрузите мультимедиа обычным способом, восстановите ADB и запустите STATUS.cmd.' -ForegroundColor Yellow
        Write-Host 'Полный экран и карта ещё не подтверждены: мост должен загрузиться после перезагрузки.'
    } elseif($Mode -eq 'RestoreBridge') {
        $restore=Recovery-Stage 'tweaks-bridge\start-ru-bridge.ps1' (@('-Mode','Restore')+$serialArgs)
        if($restore -notmatch '(?m)^(ORIGINAL_RESTORED|ALREADY_ORIGINAL)=1\r?$') { throw 'Возврат исходного модуля не подтверждён.' }
        Write-Host 'Исходный модуль Tweaks восстановлен. Перезагрузите мультимедиа обычным способом.'
    } else {
        $problems=@()
        try {
            $helper=Recovery-Stage 'helper\start-root-helper.ps1' (@('-Mode','Status')+$serialArgs)
            Recovery-Require $helper @('HELPER_READY=1','HELPER_VERSION=1.2.16','HELPER_UID_MATCH=1','BOOT_MOUNT_READ_ONLY=1','AUTOSTART_INSTALLED=1','AUTOSTART_ENABLED=1','CLUSTER_AUTO_CAPABILITY=VOYAH_CLUSTER_AUTO_1')
        } catch { $problems+=$_.Exception.Message; Write-Host $_.Exception.Message -ForegroundColor Yellow }
        try {
            $bridge=Recovery-Stage 'tweaks-bridge\start-ru-bridge.ps1' (@('-Mode','Status')+$serialArgs)
            Recovery-Require $bridge @('BRIDGE_LOADED=1')
        } catch { $problems+=$_.Exception.Message; Write-Host $_.Exception.Message -ForegroundColor Yellow }
        if($problems.Count) { throw ('Не все компоненты готовы. '+($problems -join ' ')) }
        Write-Host 'RECOVERY_READY=1' -ForegroundColor Green
        Write-Host 'Helper и загруженный мост готовы. В VoyahPlay проверьте полный экран и включите карту на приборке, затем переподключите iPhone и запустите маршрут.' -ForegroundColor Green
    }
    $exitCode=0
} catch [OperationCanceledException] { Write-Host $_.Exception.Message -ForegroundColor Yellow; $exitCode=2
} catch { Write-Host ('Операция не завершена: '+$_.Exception.Message) -ForegroundColor Red
    Write-Host 'Сохраните папки logs, helper\logs, helper\build\voyah-root-helper и tweaks-bridge\logs. Повторный заводской сброс не требуется.'
} finally { if($transcribing) { Write-Host ('Журнал: '+$log); Stop-Transcript | Out-Null } }
exit $exitCode
