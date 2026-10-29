[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$AdbDirectory, [Parameter(Mandatory=$true)][string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$out=[IO.Path]::GetFullPath($OutputDirectory)
$bundle=Join-Path $out 'Voyah-PI-compatibility'
$archive=Join-Path $out 'Voyah-PI-compatibility-1.0-with-ADB.zip'
if ((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) { throw 'Output already exists.' }
[void][IO.Directory]::CreateDirectory((Join-Path $bundle 'adb'))
$adbSource=(Resolve-Path -LiteralPath $AdbDirectory).Path
foreach ($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties')) {
    Copy-Item -LiteralPath (Join-Path $adbSource $name) -Destination (Join-Path $bundle ('adb/'+$name))
}
foreach ($name in @('start-voyah-compatibility.ps1','collect-voyah-compatibility.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $bundle $name)
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-voyah-compatibility.cmd') -Destination (Join-Path $bundle 'START.cmd')
$readme=@'
VOYAH PLAY — СБОР ДЛЯ ПРОВЕРКИ ДРУГИХ ПРОШИВОК ПИ, ВЕРСИЯ 1.0

ПЕРЕШЛИТЕ ВЕСЬ ЭТОТ ZIP ВЛАДЕЛЬЦУ АВТОМОБИЛЯ.
Это сборщик отчёта и файлов, а не установщик поддержки новой прошивки.

1. Распакуйте ВЕСЬ ZIP в обычную папку. Не запускайте файлы прямо из архива.
2. Подключите мультимедиа автомобиля к компьютеру по ADB, подтвердите отладку.
3. На стоящем автомобиле откройте VoyahPlay, если он установлен.
4. Дважды нажмите START.cmd. При нескольких устройствах выберите автомобиль
   по номеру. Если устройство одно, оно выбирается автоматически.
5. Первые 20 секунд сохраняются состояния окон. Если проверяете вспышку левого
   дока, в этот момент измените громкость и откройте штатное окно климата.
   Для обычной проверки совместимости можно просто оставить экран открытым.
6. Дождитесь зелёной строки «Готовый архив». После записи окон извлекаются файлы;
   это может занять несколько минут. Окно останется открытым до нажатия клавиши.
7. Передайте получившийся Voyah-compatibility-ДАТА-ВРЕМЯ-....zip с рабочего стола.
   Нужен именно этот НОВЫЙ архив с отчётом и файлами, а не исходный сборщик.

ADB УЖЕ В КОМПЛЕКТЕ. Устанавливать adb.exe в Windows, Android Studio, Java или
исходники проекта не требуется. Права администратора Windows не нужны.
При нескольких устройствах также можно указать serial явно:
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-voyah-compatibility.ps1 -Serial НОМЕР_УСТРОЙСТВА

Для полного набора файлов на автомобиле должен уже работать root через su 0
или уже запущенный от root adbd; оба варианта распознаются автоматически.
Сборщик root не получает и не устанавливает. Если root отсутствует, он сохранит
доступные публичные файлы и отметит недоступные. Android 9/11 и неизвестные версии
не отбрасываются; наличие VoyahPlay или root-helper для самого сбора не требуется.

ЧТО ПОПАДЁТ В ОТЧЁТ:
- версия прошивки, Android, размер и плотность экрана;
- версии, пути, SHA-256 и APK штатных CAN/Signal служб, климата/автомобиля,
  Launcher, SystemUI, QGBus, приборки, радио и подходящих OEM-служб;
- QGVehicle/QGAPI, системные SDK и оконная служба services.jar;
- ss11/ss9, загрузчик Tweaks, init.logcat.sh, его сохранённый оригинал и сведения helper;
- состояния окон, разрешений полного экрана и узкие журналы VoyahPlay;
- summary.json: список полученных файлов и точные причины отсутствующих.

Уже изученный injector определяется по полной SHA-256 и повторно не копируется;
другой выпуск сохраняется для анализа. Каждый полученный бинарный файл проверяется
по длине и SHA-256 с машины. Повреждённые или неполные файлы в ZIP не выдаются
за правильные. Общий размер ограничен; недоступный компонент не прерывает весь сбор.
Не все перечисленные компоненты присутствуют на каждой прошивке — это нормально.

Сборщик выполняет только чтение: ничего не устанавливает, не меняет настройки,
не посылает команды климату/автомобилю, не перезагружает и не перезапускает службы.
Данные сопряжения iPhone, пароли Wi-Fi, контакты, сообщения, записи звука и видео
не извлекаются. Полный список пользовательских приложений не сохраняется.
Из текстовых журналов скрываются MAC/IP и явно обозначенные персональные поля.
OEM APK/JAR и скрипты сохраняются без изменения, чтобы можно было проверить код.

Если ADB не видит машину — проверьте кабель/подключение и разрешение отладки.
При unauthorized подтвердите отладку на мультимедиа. При частичном сборе всё равно
пришлите созданный ZIP: причины записаны в report.txt и summary.json.
При ошибке Windows сохраните текст окна. Исходная папка отчёта рядом с ZIP остаётся
на рабочем столе, чтобы полученные данные не пропали при ошибке упаковки.

После анализа можно добавить поддержку конкретной прошивки. Сам факт успешного
сбора не означает, что неизвестные CAN-службы или root-helper уже поддерживаются.
'@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'),$readme,[Text.UTF8Encoding]::new($true))
$manifest=@(Get-ChildItem -LiteralPath $bundle -File -Recurse | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{file=$_.FullName.Substring($bundle.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'),(ConvertTo-Json -InputObject $manifest -Depth 4),[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Output $archive
