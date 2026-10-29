# Portable Windows launcher. Save with UTF-8 BOM for Windows PowerShell 5.1.
[CmdletBinding()]
param([string]$Serial)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

try {
    Write-Host 'VoyahPlay: сбор данных навигации на машине ПИ' -ForegroundColor Cyan
    Write-Host 'Перед сбором запустите маршрут CarPlay на iPhone.'
    Write-Host ''
    $adbPath = Join-Path $PSScriptRoot 'adb\adb.exe'
    $collector = Join-Path $PSScriptRoot 'collect-voyah-navigation.ps1'
    foreach ($file in @($adbPath, $collector, (Join-Path $PSScriptRoot 'adb\AdbWinApi.dll'),
                        (Join-Path $PSScriptRoot 'adb\AdbWinUsbApi.dll'))) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw 'Не хватает файлов комплекта. Распакуйте архив целиком в обычную папку и запустите START.cmd.'
        }
    }

    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $deviceLines = @(& $adbPath devices -l 2>&1 | ForEach-Object { $_.ToString() })
        $adbCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    if ($adbCode -ne 0) { throw ('Не удалось запустить ADB: ' + ($deviceLines -join ' ')) }
    $devices = @($deviceLines | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)(.*)$') {
            [PSCustomObject]@{ Serial = $Matches[1]; State = $Matches[2]; Details = $Matches[3].Trim() }
        }
    })
    if ($devices.Count -eq 0) {
        throw 'ADB не видит автомобиль. Проверьте подключение и отладку ADB, затем запустите START.cmd ещё раз.'
    }
    if ($Serial) {
        $selected = @($devices | Where-Object { $_.Serial -eq $Serial })
        if ($selected.Count -ne 1) { throw 'Устройство с указанным serial не найдено.' }
        $chosen = $selected[0]
    } elseif ($devices.Count -eq 1) {
        $chosen = $devices[0]
    } else {
        Write-Host 'Подключено несколько устройств. Выберите именно машину ПИ:'
        for ($i = 0; $i -lt $devices.Count; $i++) {
            Write-Host ('{0}. {1}  [{2}]  {3}' -f ($i + 1), $devices[$i].Serial, $devices[$i].State, $devices[$i].Details)
        }
        do {
            $answer = Read-Host 'Номер устройства'
            $number = 0
            $valid = [int]::TryParse($answer, [ref]$number) -and $number -ge 1 -and $number -le $devices.Count
            if (-not $valid) { Write-Host 'Введите номер из списка.' -ForegroundColor Yellow }
        } until ($valid)
        $chosen = $devices[$number - 1]
    }
    if ($chosen.State -eq 'unauthorized') {
        throw 'Подтвердите разрешение отладки на мультимедиа и запустите START.cmd ещё раз.'
    }
    if ($chosen.State -ne 'device') {
        throw 'Выбранное устройство offline. Восстановите соединение и запустите START.cmd ещё раз.'
    }
    Write-Host ('Сбор с устройства: ' + $chosen.Serial)
    Write-Host 'Настройки автомобиля не изменяются. Дождитесь создания ZIP.'
    Write-Host ''
    & $collector -Adb $adbPath -Serial $chosen.Serial
    Write-Host ''
    Write-Host 'Сбор завершён. Пришлите созданный Voyah-PI-navigation-....zip с рабочего стола.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host ''
    Write-Host ('Сбор не выполнен: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
