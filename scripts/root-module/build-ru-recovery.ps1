[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AdbDirectory,[Parameter(Mandatory=$true)][string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
$bundle=Join-Path $out 'VoyahPlay-RU-recovery-1.0'
$archive=$bundle+'-with-ADB.zip'
$components=Join-Path $out 'components'
if((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive) -or (Test-Path -LiteralPath $components)) {
    throw 'Выберите новую папку результата: комплект уже существует.'
}
$runtime=Join-Path $PSScriptRoot '../../common/src/main/assets/voyah-helper/runtime.sh'
if([IO.File]::ReadAllText([IO.Path]::GetFullPath($runtime)) -notmatch '(?m)^VERSION=1\.2\.16\r?$') { throw 'Этот комплект проверен с helper 1.2.16.' }
[void][IO.Directory]::CreateDirectory($bundle)
[void](& (Join-Path $PSScriptRoot 'build-portable.ps1') -AdbDirectory $AdbDirectory -OutputDirectory $components)
[void](& (Join-Path $PSScriptRoot 'build-ru-bridge.ps1') -AdbDirectory $AdbDirectory -OutputDirectory $components)
Copy-Item -LiteralPath (Join-Path $components 'VoyahPlay-root-helper-1.2.16') -Destination (Join-Path $bundle 'helper') -Recurse
Copy-Item -LiteralPath (Join-Path $components 'VoyahPlay-RU-bridge-1.1') -Destination (Join-Path $bundle 'tweaks-bridge') -Recurse
$bom=[Text.UTF8Encoding]::new($true)
[IO.File]::WriteAllText((Join-Path $bundle 'start-ru-recovery.ps1'),[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'start-ru-recovery.ps1')),$bom)
foreach($entry in @(@('INSTALL','Install'),@('STATUS','Status'),@('RESTORE_BRIDGE','RestoreBridge'))) {
    $cmd="@echo off`r`nsetlocal DisableDelayedExpansion`r`nchcp 65001 >nul`r`n"
    $cmd+='"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-ru-recovery.ps1" -Mode '+$entry[1]+"`r`n"
    $cmd+="set `"VOYAH_RECOVERY_EXIT=%ERRORLEVEL%`"`r`necho.`r`npause`r`nexit /b %VOYAH_RECOVERY_EXIT%`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle ($entry[0]+'.cmd')),$cmd,[Text.Encoding]::ASCII)
}
$readme=@'
VOYAHPLAY RU — ВОССТАНОВЛЕНИЕ ПОСЛЕ ЗАВОДСКОГО СБРОСА
Root helper 1.2.16 + добавка Tweaks bridge 1.1 + ADB

Этот комплект восстанавливает компоненты для полного экрана VoyahPlay
и автоматической карты CarPlay в медиакарточке приборной панели.
Отдельной установки root-helper для этого недостаточно: после переустановки
Tweaks требуется снова записать добавку 1.1 и перезагрузить мультимедиа.

ПЕРЕД УСТАНОВКОЙ
На стоящем автомобиле уже должны работать root (su 0), совместимые Tweaks
с активацией и штатной подготовкой системы, актуальная APK VoyahPlay
с helper 1.2.16 (например opack-102/103). APK в этом архиве нет.
Комплект предназначен для изученной RU-прошивки Android 11; ПИ и неизвестные
системные файлы отклоняются. Он не получает root и не активирует Tweaks.

1. Распакуйте ВЕСЬ ZIP в обычную папку Windows. Закройте CarPlay.
2. Подключите мультимедиа по ADB. Дважды нажмите INSTALL.cmd В КОРНЕ папки.
   Подпапки helper и tweaks-bridge открывать для установки не нужно.
3. Если устройство одно, оно выбирается автоматически. Если несколько,
   выберите автомобиль по номеру. Устанавливать adb.exe в Windows не нужно:
   ADB и необходимые DLL уже находятся в архиве. Java и Android Studio не нужны.
4. Установщик проверит RU-прошивку, добавит мост Tweaks, заново установит
   root helper из APK именно этого автомобиля для его текущего UID и включит
   автозапуск помощника. APK VoyahPlay и её настройки не переустанавливаются.
5. После INSTALL_WRITTEN=1 и REBOOT_REQUIRED=1 выполните ОБЫЧНУЮ ПЕРЕЗАГРУЗКУ
   мультимедиа. Повторный заводской сброс не нужен. Автоперезагрузки нет.
6. Восстановите ADB и дважды нажмите STATUS.cmd В КОРНЕ папки.
   Итог готовности компонентов: RECOVERY_READY=1. Проверяются живой helper,
   его версия и UID, автозапуск, read-only системный раздел и свежий probe
   загруженного моста 1.1 с подтверждением подготовки и подписки Tweaks.
7. Откройте VoyahPlay → Настройки → Диагностика → Root-помощник VoyahPlay.
   Проверьте помощник и расширение окна. В «Экран» включите скрытие левого
   дока и верхней панели: функции доступны после успешной проверки условий.
8. В «Дополнительно → Карта на приборке» включите карту.
   Переподключите iPhone к CarPlay и запустите маршрут. Для первого контроля
   используйте Apple «Карты», затем проверьте Яндекс Навигатор/Карты.

Пока не пройдена проверка после перезагрузки, запись моста не означает,
что его код уже работает. Если STATUS.cmd сообщил отказ, сохраните журналы;
не заменяйте системные JAR/APK файлами от другой машины.

ВОССТАНОВЛЕНИЕ ОРИГИНАЛЬНОГО МОДУЛЯ TWEAKS
RESTORE_BRIDGE.cmd возвращает сохранённый исходный ss11.bin. Затем нужна
обычная перезагрузка. Helper и его автозапуск при этом сохраняются;
полный экран и автокарта после отката добавки снова станут недоступны.
Исходный модуль хранится на машине в
/data/local/voyahplay-tweaks-bounds-test/ss11.bin.original.

ЖУРНАЛЫ
Общий журнал: logs. Подробные: helper/logs,
helper/build/voyah-root-helper и tweaks-bridge/logs.
При ошибке пришлите эти папки. Манифест FILES-SHA256.json проверяется
до обращения к автомобилю; вложенные установщики сохраняют свои проверки.

Для явного выбора serial (не требуется при одном устройстве):
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-ru-recovery.ps1 -Mode Install -Serial НОМЕР

Пакет не содержит данных сопряжения iPhone, активации или файлов владельца.
В подпапках adb — оригинальные Android Platform-Tools и их лицензии NOTICE.txt.
3 ноября 2026 объединённый установщик проверен на изученной RU после сброса:
APK opack-103, helper 1.2.16, мост 1.1, перезагрузка и STATUS.cmd.
Полный экран восстановлен, живая карта на приборке подтверждена владельцем.
Эта проверка не распространяется на другие прошивки или другие причины
отсутствия видеопотока карты от iPhone.
'@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'),$readme,$bom)
$manifest=@(Get-ChildItem -LiteralPath $bundle -Recurse -File | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{file=$_.FullName.Substring($bundle.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'),($manifest | ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Output $archive
