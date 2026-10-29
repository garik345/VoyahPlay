[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$out = [IO.Path]::GetFullPath($OutputDirectory)
$bundle = Join-Path $out 'VoyahPlay-PI-fullscreen'
$archive = Join-Path $out 'VoyahPlay-PI-opack-75-with-ADB.zip'
if ((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) { throw 'Output already exists.' }
$baseArchive = & (Join-Path $PSScriptRoot 'build-portable.ps1') -AdbDirectory $AdbDirectory -OutputDirectory (Join-Path $out 'helper')
$baseBundle = $baseArchive -replace '-with-ADB\.zip$', ''
Copy-Item -LiteralPath $baseBundle -Destination $bundle -Recurse
Copy-Item -LiteralPath $ApkPath -Destination (Join-Path $bundle 'VoyahPlay.apk')
foreach ($file in @('prepare-pi-fullscreen.ps1','pi-tweaks-bridge.sh')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $bundle $file)
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pi-bridge') -Destination (Join-Path $bundle 'pi-bridge') -Recurse
$json = @{ apkSha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant(); revision = 'opack-75-pi-ota72'; installerRevision = '1.0.2' }
[IO.File]::WriteAllText((Join-Path $bundle 'pi-package.json'), ($json | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
foreach ($operation in @('INSTALL','RESTORE_PI_BRIDGE')) {
    $arguments = if ($operation -eq 'INSTALL') { '-Mode Install -PreparePiFullscreen' } else { '-Mode Status -RestorePiFullscreen' }
    $cmd = "@echo off`r`nsetlocal DisableDelayedExpansion`r`nchcp 65001 >nul`r`ntitle VoyahPlay - PI fullscreen`r`n"
    $cmd += '"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-root-helper.ps1" ' + $arguments + "`r`n"
    $cmd += "set `"VOYAH_HELPER_EXIT=%ERRORLEVEL%`"`r`necho.`r`npause`r`nexit /b %VOYAH_HELPER_EXIT%`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle ($operation + '.cmd')), $cmd, [Text.Encoding]::ASCII)
}
$readme = @'
VOYAHPLAY — ПИ OTA7.0 / OTA7.2, OPACK-75 / HELPER 1.2.10

Комплект содержит APK VoyahPlay, root-helper и adb.exe. Поддержаны проверенные
пары прошивок ПИ OTA7.0 build 86 и OTA7.2 build 35 из выгрузок владельцев.
У новой OTA7.2 добавлены CAN/климат/данные автомобиля, точная пара cluster/QGAPI
для навигационных подсказок и совместимость полного экрана с её Launcher.
Физическая проверка этой новой машины ещё требуется.

1. Припаркованный автомобиль подключите по ADB. Завершите звонок и закройте CarPlay.
2. Распакуйте ВЕСЬ ZIP в обычную папку. Запустите INSTALL.cmd двойным щелчком.
3. При нескольких ADB-устройствах выберите автомобиль по номеру. При одном он
   выбирается автоматически. Устанавливать adb.exe отдельно в Windows не нужно.
4. Дождитесь завершения: проверяется точная прошивка, обновляется APK с сохранением
   данных, сохраняется оригинал ss11.bin, ставится добавка 1.1 и helper 1.2.10.
5. Если установщик запросил перезагрузку, перезагрузите мультимедиа обычным способом.
   Автоматической перезагрузки нет. При уже загруженной добавке 1.1 повторная
   перезагрузка только ради обновления helper не требуется.
6. Запустите STATUS.cmd. В VoyahPlay проверьте root-helper 1.2.10, затем включите
   «Климат и комфорт через CarPlay Ultra», если выключено. В диагностике проверьте
   данные климата и переподключите iPhone. Для полного экрана включите скрытие
   левой панели и верхней строки в настройках экрана.
7. На стоящей машине сначала проверьте показания климата/сидений/давления/заряда,
   затем одну небольшую смену температуры с возвратом исходного значения.
   Проверьте полный экран, HOME/возврат, окна громкости/климата, экран пассажира.

На новой машине OTA7.2 в выгрузке helper отсутствует, а ss11.bin исходный:
для полного экрана первичная установка и обычная перезагрузка обязательны.
На машине уже должны работать root (su 0), VoyahTweaks и проверенный injector.
Их установка и условия подписки этим комплектом не меняются.
Другие services.jar, SystemUI или injector установщик отклоняет до обновления APK.
Пары компонентов разных прошивок не принимаются. Системные APK/JAR не заменяются.
Защита дока применяется только к двум изученным Launcher на водительском экране,
при активном VoyahPlay и прежних условиях готовности/подписки Tweaks.
Проверка этой защиты на OTA7.2 требуется: при конфликте с другим обработчиком
метод не заменяется. Отчёт границ окна содержит DOCK_GUARD_PROBE с причиной.

В выгрузке OTA7.2 штатное приложение com.pateo.rdsapp не найдено. Если в этой
комплектации нет радиоприёмника, отключите «Радио» в настройках VoyahPlay.
Подогрев руля на непроверенной комплектации ПИ не объявляется автоматически.
Навигационные подсказки проверяйте отдельно с включённым маршрутом.

Повторный INSTALL.cmd сохраняет исходную резервную копию моста.
Откат добавки: RESTORE_PI_BRIDGE.cmd, затем обычная перезагрузка мультимедиа.
Восстанавливается только проверенный оригинал этой машины; APK и helper остаются.
Резервная копия: /data/local/voyahplay-tweaks-bounds-test/ss11.bin.original
Журналы Windows: logs и build/voyah-root-helper рядом с INSTALL.cmd.
При ошибке сохраните журналы и текст диагностики, APK не удаляйте.
FILES-SHA256.json содержит контрольные суммы комплекта. OEM-файлы из выгрузки
в комплект не включены. Предыдущие исправления звука opack-74 сохранены;
их слышимость на этой машине ещё нужно проверить.
'@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'), $readme, [Text.UTF8Encoding]::new($true))
$manifest = @(Get-ChildItem -LiteralPath $bundle -File -Recurse | Where-Object { $_.Name -ne 'FILES-SHA256.json' } | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{ file = $_.FullName.Substring($bundle.Length + 1).Replace('\','/'); bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'), ($manifest | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle, $archive, [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Output $archive
