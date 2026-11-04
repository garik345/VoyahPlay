# Portable RU bridge repair. The package builder writes a UTF-8 BOM for Windows PowerShell 5.1.
[CmdletBinding()]
param(
    [ValidateSet('Install','Status','Restore')][string]$Mode = 'Status',
    [string]$Serial,
    [string]$Adb
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference = $false }
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$script:Target = @()
$transcribing = $false
$exitCode = 1
$remoteScript = $null
$remotePayload = $null
$rootVerified = $false
$patchedHash = '575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d'
function Get-RuFileSha([string]$Path) {
    $inputStream = [IO.File]::OpenRead($Path)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($inputStream))).Replace('-','').ToLowerInvariant() }
    finally { $digest.Dispose(); $inputStream.Dispose() }
}
function Invoke-RuAdb([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw ('Ошибка ADB: ' + ($lines -join "`n")) }
    return ($lines -join "`n")
}
try {
    Write-Host 'VoyahPlay — восстановление добавки Tweaks 1.1 для RU' -ForegroundColor Cyan
    $logs = Join-Path $PSScriptRoot 'logs'
    [void][IO.Directory]::CreateDirectory($logs)
    $log = Join-Path $logs ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.txt')
    Start-Transcript -LiteralPath $log -Force | Out-Null
    $transcribing = $true
    # Hash every shipped file before any device command; include the executable and DLLs.
    $manifest = ConvertFrom-Json -InputObject ([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'FILES-SHA256.json')))
    $manifestNames = @($manifest | ForEach-Object { $_.file })
    foreach ($required in @('start-ru-bridge.ps1','bridge-installer.sh','bridge/ss11.bin','bridge/verification.json',
                           'INSTALL.cmd','STATUS.cmd','RESTORE.cmd','adb/adb.exe','adb/AdbWinApi.dll','adb/AdbWinUsbApi.dll','adb/NOTICE.txt','adb/source.properties')) {
        if (@($manifestNames | Where-Object { $_ -ceq $required }).Count -ne 1) { throw ('Неполный манифест комплекта: ' + $required) }
    }
    foreach ($entry in $manifest) {
        if ($entry.file -notmatch '^[A-Za-z0-9_.\-/]+$' -or $entry.file -match '(^|/)\.\.(/|$)' -or
            $entry.file -match '^[/\\]' -or $entry.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Некорректный манифест комплекта.' }
        $file = Join-Path $PSScriptRoot $entry.file
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
            (Get-RuFileSha $file) -ne $entry.sha256) {
            throw ('Повреждён файл комплекта: ' + $entry.file + '. Распакуйте весь ZIP заново.')
        }
    }
    $payload = Join-Path $PSScriptRoot 'bridge\ss11.bin'
    $deviceScript = Join-Path $PSScriptRoot 'bridge-installer.sh'
    if ((Get-RuFileSha $payload) -ne $patchedHash) {
        throw 'Контрольная сумма добавки не совпадает с проверенной.'
    }
    $script:AdbExe = if ($Adb) { $Adb } else { Join-Path $PSScriptRoot 'adb\adb.exe' }
    if (-not (Test-Path -LiteralPath $script:AdbExe -PathType Leaf)) { throw 'ADB не найден. Распакуйте весь комплект.' }
    $devices = @((Invoke-RuAdb -Arguments @('devices','-l')) -split "`r?`n" | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)(.*)$') {
            [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2];Details=$Matches[3].Trim()}
        }
    })
    if ($devices.Count -eq 0) { throw 'ADB не видит автомобиль. Включите отладку и восстановите подключение.' }
    if ($Serial) {
        $selected = @($devices | Where-Object { $_.Serial -eq $Serial })
        if ($selected.Count -ne 1) { throw 'Устройство с указанным serial не найдено.' }
        $chosen = $selected[0]
    } elseif ($devices.Count -eq 1) { $chosen = $devices[0] }
    else {
        for ($i=0; $i -lt $devices.Count; $i++) { Write-Host ('{0}. {1} [{2}] {3}' -f ($i+1),$devices[$i].Serial,$devices[$i].State,$devices[$i].Details) }
        do {
            $answer = Read-Host 'Номер автомобиля (0 — отмена)'
            if ([string]::IsNullOrWhiteSpace($answer) -or $answer -eq '0') { throw [OperationCanceledException]::new('Операция отменена.') }
            $selection = 0
            $valid = [int]::TryParse($answer,[ref]$selection) -and $selection -ge 1 -and $selection -le $devices.Count
        } until ($valid)
        $chosen = $devices[$selection-1]
    }
    if ($chosen.State -eq 'unauthorized') { throw 'Подтвердите отладку на мультимедиа и повторите запуск.' }
    if ($chosen.State -ne 'device') { throw 'Автомобиль offline. Восстановите подключение.' }
    $script:Target = @('-s',$chosen.Serial)
    Write-Host ('Выбран автомобиль: ' + $chosen.Serial)
    if ((Invoke-RuAdb -Arguments @('shell','getprop','ro.build.version.sdk')).Trim() -ne '30') { throw 'Нужна проверенная RU-прошивка Android 11.' }
    if ((Invoke-RuAdb -Arguments @('shell','su','0','id')) -notmatch '(?m)^uid=0(?:\(|\s|$)') { throw 'Не подтверждён существующий root (su 0).' }
    $rootVerified = $true
    $token = [guid]::NewGuid().ToString('N')
    $remoteScript = '/data/local/tmp/voyah-bounds-' + $token + '.sh'
    $remotePayload = '/data/local/tmp/voyah-bounds-' + $token + '.bin'
    Write-Host (Invoke-RuAdb -Arguments @('push',$deviceScript,$remoteScript))
    $check = Invoke-RuAdb -Arguments @('shell','su','0','sh',$remoteScript,'check',$remotePayload,'RU')
    Write-Host $check
    if ($check -notmatch '(?m)^RU_FIRMWARE_VERIFIED=1\r?$') { throw 'Совместимость RU не подтверждена. Файлы не заменены.' }
    if ($Mode -eq 'Install') {
        Write-Host (Invoke-RuAdb -Arguments @('push',$payload,$remotePayload))
        $result = Invoke-RuAdb -Arguments @('shell','su','0','sh',$remoteScript,'install',$remotePayload,'RU')
        Write-Host $result
        if ($result -notmatch '(?m)^(RU_BRIDGE_INSTALLED|ALREADY_INSTALLED)=1\r?$') { throw 'Установка добавки не подтверждена.' }
        Write-Host 'Добавка записана. Перезагрузите мультимедиа обычным способом, затем запустите STATUS.cmd.' -ForegroundColor Yellow
        Write-Host 'APK VoyahPlay и root-helper не менялись. Перезагрузка автоматически не выполняется.'
    } elseif ($Mode -eq 'Restore') {
        $result = Invoke-RuAdb -Arguments @('shell','su','0','sh',$remoteScript,'restore',$remotePayload,'RU')
        Write-Host $result
        if ($result -notmatch '(?m)^(ORIGINAL_RESTORED|ALREADY_ORIGINAL)=1\r?$') { throw 'Восстановление оригинала не подтверждено.' }
        Write-Host 'Исходный модуль восстановлен. Перезагрузите мультимедиа обычным способом.' -ForegroundColor Yellow
    } else {
        if ($check -notmatch ('(?m)^CURRENT_BRIDGE_SHA256=' + $patchedHash + '\r?$')) { throw 'Установлен исходный модуль Tweaks. Сначала запустите INSTALL.cmd.' }
        $probeLine = @($check -split "`r?`n" | Where-Object { $_.StartsWith('BOUNDS_PROBE_JSON=') })
        if ($probeLine.Count -ne 1) { throw 'Нет отчёта загруженной добавки. Перезагрузите мультимедиа.' }
        try { $probe = $probeLine[0].Substring('BOUNDS_PROBE_JSON='.Length) | ConvertFrom-Json }
        catch { throw 'Нет корректного отчёта загруженной добавки. Перезагрузите мультимедиа.' }
        $systemPid = (Invoke-RuAdb -Arguments @('shell','pidof','system_server')).Trim()
        $uptime = (Invoke-RuAdb -Arguments @('shell','cat','/proc/uptime')).Trim().Split(' ')[0]
        $nowMs = [double]::Parse($uptime,[Globalization.CultureInfo]::InvariantCulture)*1000
        if ($null -eq $probe -or $probe.version -ne '1.1' -or "$($probe.pid)" -ne $systemPid -or
            -not ($probe.boot_ready -is [bool]) -or -not ($probe.subscription_valid -is [bool]) -or
            -not ($probe.now_ms -is [int] -or $probe.now_ms -is [long] -or $probe.now_ms -is [double]) -or
            $probe.boot_ready -ne $true -or $probe.subscription_valid -ne $true -or
            $probe.now_ms -gt $nowMs -or $nowMs-$probe.now_ms -gt 5000) {
            throw 'Добавка записана, но её работа ещё не подтверждена. Проверьте активацию Tweaks и перезагрузите мультимедиа.'
        }
        Write-Host 'BRIDGE_LOADED=1' -ForegroundColor Green
        Write-Host 'Добавка Tweaks 1.1 загружена. В VoyahPlay проверьте helper, полный экран и карту.' -ForegroundColor Green
    }
    $exitCode = 0
} catch [OperationCanceledException] {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
    $exitCode = 2
} catch {
    Write-Host ('Операция не завершена: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Пришлите журнал из папки logs.'
} finally {
    if ($rootVerified -and $remoteScript -and $remotePayload) {
        try { [void](Invoke-RuAdb -Arguments @('shell','su','0','rm','-f',$remoteScript,$remotePayload)) }
        catch { Write-Host ('Не удалось убрать временные файлы: ' + $_.Exception.Message) -ForegroundColor Yellow }
    }
    if ($transcribing) { Write-Host ('Журнал: ' + $log); Stop-Transcript | Out-Null }
}
exit $exitCode
