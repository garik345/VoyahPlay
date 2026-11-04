[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
$bundle=Join-Path $out 'VoyahPlay-RU-bridge-1.1'
$archive=$bundle+'-with-ADB.zip'
if((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)){throw 'Выберите новую папку результата: комплект уже существует.'}
$adbSource=(Resolve-Path -LiteralPath $AdbDirectory).Path
$payload=Join-Path $PSScriptRoot 'pi-bridge\ss11.bin'
if((Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant() -ne '575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d'){throw 'Не совпадает сумма проверенной добавки.'}
$adbFiles=@('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties')
foreach($name in $adbFiles){if(-not(Test-Path -LiteralPath (Join-Path $adbSource $name) -PathType Leaf)){throw "Нет файла ADB: $name"}}
[void][IO.Directory]::CreateDirectory((Join-Path $bundle 'adb'))
[void][IO.Directory]::CreateDirectory((Join-Path $bundle 'bridge'))
$bom=[Text.UTF8Encoding]::new($true)
$plain=[Text.UTF8Encoding]::new($false)
$launcher=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'start-ru-bridge.ps1'))
[IO.File]::WriteAllText((Join-Path $bundle 'start-ru-bridge.ps1'),$launcher,$bom)
$shell=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'pi-tweaks-bridge.sh')).Replace("`r`n","`n")
[IO.File]::WriteAllText((Join-Path $bundle 'bridge-installer.sh'),$shell,$plain)
Copy-Item -LiteralPath $payload -Destination (Join-Path $bundle 'bridge\ss11.bin')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pi-bridge\verification.json') -Destination (Join-Path $bundle 'bridge\verification.json')
foreach($name in $adbFiles){Copy-Item -LiteralPath (Join-Path $adbSource $name) -Destination (Join-Path $bundle ('adb\'+$name))}
foreach($entry in @(@('INSTALL','Install'),@('STATUS','Status'),@('RESTORE','Restore'))){
    $cmd=@"
@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
title VoyahPlay - RU bridge 1.1
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-ru-bridge.ps1" -Mode $($entry[1])
set "VOYAH_RU_BRIDGE_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %VOYAH_RU_BRIDGE_EXIT%
"@
    [IO.File]::WriteAllText((Join-Path $bundle ($entry[0]+'.cmd')),$cmd.Replace("`r`n","`n").Replace("`n","`r`n"),[Text.Encoding]::ASCII)
}
$readme=@"
VOYAHPLAY — ДОБАВКА TWEAKS 1.1 ДЛЯ ПРОВЕРЕННОЙ RU-ПРОШИВКИ

Для ошибки TWEAKS_BRIDGE_UPGRADE_REQUIRED после сброса или переустановки Tweaks.
Дополнение требуется для расширения окна VoyahPlay и автоматической карты в медиакарточке приборки.

1. Заранее установите и активируйте совместимые Tweaks. VoyahPlay opack-86 и helper 1.2.15 должны быть установлены.
2. Распакуйте ВЕСЬ ZIP в обычную папку Windows. Не запускайте файлы из архива.
3. Подключите стоящий автомобиль по ADB. Закройте CarPlay на время подготовки.
4. Дважды нажмите INSTALL.cmd. При нескольких устройствах выберите номер автомобиля; одно выбирается автоматически.
5. Дождитесь сообщения «Добавка записана». Выполните обычную перезагрузку мультимедиа самостоятельно.
6. Восстановите ADB и запустите STATUS.cmd. Успешная проверка загруженного модуля: BRIDGE_LOADED=1.
7. Откройте VoyahPlay, включите скрытие дока и статус-бара, проверьте расширение окна.
8. В «Дополнительно → Карта на приборке» включите карту, переподключите CarPlay и запустите маршрут Apple «Карт».

ADB включён в комплект. Java, Android Studio и установка ADB в Windows не нужны.
Нужен уже действующий root на мультимедиа (su 0). Комплект не получает root,
не устанавливает Tweaks, не меняет APK VoyahPlay, root-helper, активацию или условия подписки.
Перезагрузка не выполняется автоматически. Пока она не сделана, записанный модуль может ещё не работать.
Установщик не изменяет прошивку/system: заменяет только /data/local/tmp/ss11.bin.

Проверяются точные суммы services.jar, SystemUI, injector и исходного модуля RU.
Неизвестная версия или чужая резервная копия останавливают установку. Случайные файлы ПИ сюда не подходят.
Исходный ss11.bin сохраняется в /data/local/voyahplay-tweaks-bounds-test/ss11.bin.original.
Для отката запустите RESTORE.cmd и выполните обычную перезагрузку мультимедиа.
RESTORE не отменяет настройки VoyahPlay и не удаляет helper; полная ширина/автокарта после отката будут недоступны.

Журналы: папка logs. При отказе пришлите журнал; повторный сброс мультимедиа не требуется.
Оригинальные файлы Android Platform-Tools находятся в adb, лицензия — adb/NOTICE.txt.
FILES-SHA256.json содержит контрольные суммы поставки.
"@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'),$readme,$bom)
$manifest=@(Get-ChildItem -LiteralPath $bundle -File -Recurse | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{file=$_.FullName.Substring($bundle.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'),($manifest | ConvertTo-Json -Depth 3),$plain)
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Output $archive
