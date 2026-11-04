[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ApkPath,[Parameter(Mandatory=$true)][string]$AdbDirectory,[Parameter(Mandatory=$true)][string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
$bundle=Join-Path $out 'VoyahPlay-phone-key-opack-100'
$archive=$bundle+'-with-ADB.zip'
if ((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) { throw 'Choose a new output directory.' }
[void][IO.Directory]::CreateDirectory((Join-Path $bundle 'adb'))
foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties')) {
    Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination (Join-Path $bundle ('adb/'+$name))
}
$bom=[Text.UTF8Encoding]::new($true)
[IO.File]::WriteAllText((Join-Path $bundle 'start-voyah-phone-probe.ps1'),[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'start-voyah-phone-probe.ps1')),$bom)
Copy-Item -LiteralPath $ApkPath -Destination (Join-Path $bundle 'VoyahPlay-opack-100-phone-probe-receiver-debug.apk')
foreach($entry in @(@('INSTALL','Install'),@('OPEN_RECENTS','OpenRecents'),@('WHEEL_TEST','WheelTest'),@('STOP_TEST','Stop'))) {
    $cmd="@echo off`r`nsetlocal DisableDelayedExpansion`r`nchcp 65001 >nul`r`n"
    $cmd+='"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-voyah-phone-probe.ps1" -Mode '+$entry[1]+"`r`n"
    $cmd+="set `"VOYAH_TEST_EXIT=%ERRORLEVEL%`"`r`necho.`r`npause`r`nexit /b %VOYAH_TEST_EXIT%`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle ($entry[0]+'.cmd')),$cmd,[Text.Encoding]::ASCII)
}
$readme=@'
VOYAHPLAY OPACK-100 — ФОНОВЫЙ ТЕСТ КНОПКИ ТРУБКИ

В opack-99 диагностическое окно могло возвращать Android в меню VoyahPlay.
В opack-100 тест запускается фоновой командой, без открытия и закрытия окон.
Каждая команда получает свой номер. В отчёте сохраняются подтверждение её
получения приложением и отдельный журнал. От logcat тест больше не зависит.

Этот комплект для изученной RU-прошивки 650. Другие KeyManager/QGAPI отклоняются
по SHA-256. Tweaks и root-helper не переустанавливаются и не изменяются.
Перехват не включается автоматически и не сохраняется в настройках.

Проверяйте на стоящем автомобиле БЕЗ активного звонка.
1. Распакуйте ВЕСЬ ZIP. Подключите автомобиль по ADB.
2. Запустите INSTALL.cmd. Обновление APK прервёт CarPlay; после установки
   откройте VoyahPlay и дождитесь нового подключения iPhone.
3. Запустите OPEN_RECENTS.cmd. Он только просит iPhone открыть «Недавние».
   Дождитесь сообщения «Команда получена приложением и отправлена iPhone».
   Напишите, что появилось на экране. RECENTS_DELIVERED с detail=true доказывает
   отправку, но не поддержку этого адреса самим CarPlay.
4. Если нужный список открылся, вернитесь в карту или меню приложений CarPlay.
   Запустите WHEEL_TEST.cmd, дождитесь начала теста и нажмите трубку ОДИН раз.
   Уточните, открылись «Недавние» CarPlay или штатная Bluetooth-звонилка.
5. Через 30 секунд обработчик автоматически выключится. Убедитесь, что
   после окончания теста короткое нажатие снова вызывает штатное действие.
   Для досрочной остановки есть STOP_TEST.cmd. Приложите папку logs к результату:
   даже при ошибке туда записывается ответ запуска и доступный журнал приложения.

Если OPEN_RECENTS.cmd снова изменяет экран VoyahPlay, передайте получившийся
отчёт: он покажет, получил ли iPhone запрос и запросил ли затем штатный интерфейс.
До правильного открытия «Недавних» к WHEEL_TEST.cmd переходить не нужно.

Временный обработчик работает только в исходной подключённой сессии CarPlay,
без звонка. Перехватывается только короткое нажатие 128/8192; остальные клавиши,
DOWN/UP и длинное нажатие проходят штатно. Запросы набора и повтора номера
не отправляются. Код Tweaks/keymanager.bin и телефонные данные не меняются.
Доставка события и правильное открытие «Недавних» ещё требуют проверки на машине.

ADB включён в комплект, устанавливать его в Windows не нужно. Если устройств
несколько, выберите автомобиль по номеру. Serial можно передать явно:
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-voyah-phone-probe.ps1 -Mode WheelTest -Serial НОМЕР

Тест доступен только через ADB/root: обычные приложения не имеют права запускать
его компонент. В настройках VoyahPlay дополнительного переключателя пока нет.
После окончания теста можно остаться на этой APK с выключенным перехватом
или установить обратно сохранённую opack-98.
'@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'),$readme,$bom)
$manifest=@(Get-ChildItem -LiteralPath $bundle -Recurse -File | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{file=$_.FullName.Substring($bundle.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'),(ConvertTo-Json -InputObject $manifest -Depth 4),[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Output $archive
