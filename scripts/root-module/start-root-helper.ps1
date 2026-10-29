# Portable Windows launcher. Distributed as UTF-8 with BOM for Windows PowerShell 5.1.
[CmdletBinding()]
param(
    [ValidateSet('Install','Status')][string]$Mode = 'Install',
    [string]$Serial,
    [switch]$PreparePiFullscreen,
    [switch]$RestorePiFullscreen
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$exitCode = 1
$transcribing = $false
$script:rebootRequired = $false
$script:mountReadOnly = $null
$script:Target = @()

function Invoke-LauncherAdb([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw ('Ошибка ADB: ' + ($lines -join ' ')) }
    return ($lines -join "`n")
}

function Invoke-Installer([string]$Operation) {
    # Core installer throws on failure and validates HELPER_READY, version and capabilities.
    # Stream host output live, retaining its reboot indicators for a plain-language result.
    & $script:Installer -Mode $Operation -ProjectRoot $PSScriptRoot -Adb $script:AdbExe -Serial $script:ChosenSerial 6>&1 |
        ForEach-Object {
            $line = $_.ToString()
            Write-Host $line
            if ($line -match '^REBOOT_REQUIRED=1\r?$') { $script:rebootRequired = $true }
            if ($line -match '^BOOT_MOUNT_READ_ONLY=([01])\r?$') { $script:mountReadOnly = $Matches[1] -eq '1' }
        }
}

try {
    if ($PreparePiFullscreen -and $RestorePiFullscreen) { throw 'Choose either PI installation or restoration.' }
    Write-Host 'VoyahPlay — root helper 1.2.10' -ForegroundColor Cyan
    Write-Host $(if ($Mode -eq 'Install') { 'Установка и проверка помощника' } else { 'Проверка установленного помощника' })
    Write-Host ''
    $logs = Join-Path $PSScriptRoot 'logs'
    [void][IO.Directory]::CreateDirectory($logs)
    $log = Join-Path $logs ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '.txt')
    Start-Transcript -LiteralPath $log -Force | Out-Null
    $transcribing = $true
    $script:AdbExe = Join-Path $PSScriptRoot 'adb\adb.exe'
    $script:Installer = Join-Path $PSScriptRoot 'root-helper.ps1'
    foreach ($file in @($script:AdbExe, $script:Installer, (Join-Path $PSScriptRoot 'root-helper-bootstrap.sh'),
                        (Join-Path $PSScriptRoot 'adb\AdbWinApi.dll'), (Join-Path $PSScriptRoot 'adb\AdbWinUsbApi.dll'))) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw 'Не хватает файлов комплекта. Распакуйте весь архив в обычную папку и повторите запуск.'
        }
    }
    $deviceLines = Invoke-LauncherAdb -Arguments @('devices','-l')
    $devices = @($deviceLines -split "`r?`n" | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)(.*)$') {
            [PSCustomObject]@{ Serial = $Matches[1]; State = $Matches[2]; Details = $Matches[3].Trim() }
        }
    })
    if ($devices.Count -eq 0) { throw 'ADB не видит автомобиль. Подключите мультимедиа по ADB и повторите запуск.' }
    if ($Serial) {
        $selected = @($devices | Where-Object { $_.Serial -eq $Serial })
        if ($selected.Count -ne 1) { throw 'Устройство с указанным serial не найдено.' }
        $chosen = $selected[0]
    } elseif ($devices.Count -eq 1) {
        $chosen = $devices[0]
    } else {
        Write-Host 'Выберите автомобиль из списка подключённых устройств:'
        for ($i = 0; $i -lt $devices.Count; $i++) {
            Write-Host ('{0}. {1}  [{2}]  {3}' -f ($i + 1), $devices[$i].Serial, $devices[$i].State, $devices[$i].Details)
        }
        do {
            $answer = Read-Host 'Номер устройства (0 — отмена)'
            if ([string]::IsNullOrWhiteSpace($answer) -or $answer -eq '0') {
                throw [OperationCanceledException]::new('Операция отменена. Установщик не запускался.')
            }
            $number = 0
            $valid = [int]::TryParse($answer, [ref]$number) -and $number -ge 1 -and $number -le $devices.Count
            if (-not $valid) { Write-Host 'Введите номер из списка.' -ForegroundColor Yellow }
        } until ($valid)
        $chosen = $devices[$number - 1]
    }
    if ($chosen.State -eq 'unauthorized') { throw 'Подтвердите разрешение отладки на мультимедиа и повторите запуск.' }
    if ($chosen.State -ne 'device') { throw 'Выбранное устройство offline. Восстановите соединение и повторите запуск.' }
    $script:ChosenSerial = $chosen.Serial
    $script:Target = @('-s', $chosen.Serial)
    Write-Host ('Выбран автомобиль: ' + $chosen.Serial)
    if ((Invoke-LauncherAdb -Arguments @('shell','getprop','ro.build.version.sdk')).Trim() -ne '30') {
        throw 'Этот помощник предназначен для проверенных прошивок Android 11.'
    }
    if ($Mode -eq 'Install') {
        $installed = Invoke-LauncherAdb -Arguments @('shell','pm','path','--user','0','com.shilapi.xcertplay')
        if ($installed -notmatch '(?m)^package:.+\.apk\r?$') {
            throw 'Сначала установите актуальный APK VoyahPlay на основной экран автомобиля, затем повторите запуск.'
        }
    }
    try { $identity = Invoke-LauncherAdb -Arguments @('shell','su','0','id') }
    catch { throw ('Для установки и проверки нужен уже имеющийся root на автомобиле (su 0). ' + $_.Exception.Message) }
    if ($identity -notmatch '(?m)^uid=0(?:\(|\s|$)') {
        throw 'Доступ root через ADB не подтверждён. На автомобиле должен уже работать su 0; этот комплект root не устанавливает.'
    }
    Write-Host ''
    if ($PreparePiFullscreen -or $RestorePiFullscreen) {
        $piPreparation = Join-Path $PSScriptRoot 'prepare-pi-fullscreen.ps1'
        if ($RestorePiFullscreen) {
            & $piPreparation -Mode Restore -Adb $script:AdbExe -Serial $script:ChosenSerial
            $exitCode = 0
            exit $exitCode
        }
        if ($Mode -ne 'Install') { throw 'PI preparation requires Install mode.' }
        & $piPreparation -Mode Install -Adb $script:AdbExe -Serial $script:ChosenSerial 6>&1 |
            ForEach-Object {
                $line = $_.ToString(); Write-Host $line
                if ($line -match '^REBOOT_REQUIRED=1\r?$') { $script:rebootRequired = $true }
            }
    }
    if ($Mode -eq 'Install') {
        Invoke-Installer 'Install'
        Write-Host ''
        Write-Host 'Установка выполнена. Проверяю состояние...' -ForegroundColor Cyan
    }
    Invoke-Installer 'Status'
    Write-Host ''
    if ($script:rebootRequired -or $script:mountReadOnly -eq $false) {
        Write-Host 'Помощник установлен и отвечает. Перезагрузите мультимедиа обычным способом, затем запустите STATUS.cmd.' -ForegroundColor Yellow
    } else {
        Write-Host $(if ($Mode -eq 'Install') { 'Готово. Root helper 1.2.10 установлен, состояние проверено.' } else { 'Проверка пройдена. Root helper 1.2.10 отвечает.' }) -ForegroundColor Green
    }
    $exitCode = 0
} catch [OperationCanceledException] {
    Write-Host $_.Exception.Message -ForegroundColor Yellow
    $exitCode = 2
} catch {
    Write-Host ''
    Write-Host ('Операция не завершена: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'При обращении за помощью приложите журнал из папки logs.'
} finally {
    if ($transcribing) {
        Write-Host ('Журнал запуска: ' + $log)
        Write-Host ('Подробные журналы помощника: ' + (Join-Path $PSScriptRoot 'build\voyah-root-helper'))
        Stop-Transcript | Out-Null
    }
}
exit $exitCode
