# PowerShell 5.1+. Package only the explicit installer and official Platform-Tools files.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AdbDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runtime = [IO.File]::ReadAllText((Join-Path $project 'common\src\main\assets\voyah-helper\runtime.sh'))
$version = [regex]::Match($runtime, '(?m)^VERSION=([0-9.]+)\r?$').Groups[1].Value
if (-not $version) { throw 'Cannot read helper version.' }
$adbSource = (Resolve-Path -LiteralPath $AdbDirectory).Path
$out = [IO.Path]::GetFullPath($OutputDirectory)
[void][IO.Directory]::CreateDirectory($out)
$bundle = Join-Path $out ('VoyahPlay-root-helper-' + $version)
$archive = $bundle + '-with-ADB.zip'
if ((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) {
    throw 'Bundle output already exists. Choose a new output directory.'
}
$adbFiles = @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties')
$scripts = @('start-root-helper.ps1','root-helper.ps1','root-helper-bootstrap.sh','install-root-helper.cmd','status-root-helper.cmd')
foreach ($name in $adbFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $adbSource $name) -PathType Leaf)) { throw "Missing Platform-Tools file: $name" }
}
foreach ($name in $scripts) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) { throw "Missing installer file: $name" }
}
[void][IO.Directory]::CreateDirectory((Join-Path $bundle 'adb'))
foreach ($name in $adbFiles) { Copy-Item -LiteralPath (Join-Path $adbSource $name) -Destination (Join-Path $bundle ('adb\' + $name)) }
foreach ($name in @('start-root-helper.ps1','root-helper.ps1','root-helper-bootstrap.sh')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $bundle $name)
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-root-helper.cmd') -Destination (Join-Path $bundle 'INSTALL.cmd')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'status-root-helper.cmd') -Destination (Join-Path $bundle 'STATUS.cmd')
$readme = @"
VOYAHPLAY — ROOT HELPER $version

Установка в Windows двойным щелчком

1. Установите актуальный APK VoyahPlay на мультимедиа. В этом комплекте APK нет.
2. Подключите автомобиль к компьютеру по ADB. На время установки закройте CarPlay.
3. Распакуйте ВЕСЬ ZIP в обычную папку. Не запускайте файлы прямо из архива.
4. Дважды нажмите INSTALL.cmd. Если устройств несколько, выберите автомобиль
   по номеру в списке. Если устройство одно, оно выбирается автоматически.
5. Дождитесь сообщения об установке и проверке. Окно останется открытым.
   Закройте его нажатием любой клавиши.

ADB уже входит в архив. Устанавливать ADB в Windows, Java, Android Studio
или исходники VoyahPlay для этого запуска не нужно. Права администратора Windows
не требуются.

НА АВТОМОБИЛЕ УЖЕ ДОЛЖНЫ БЫТЬ:
- совместимая прошивка Android 11 и установленный VoyahPlay с helper $version;
- существующий доступ root через ADB: su 0;
- совместимые компоненты Tweaks и injector для функций полного экрана/радио.
Этот комплект устанавливает помощник VoyahPlay. Root, Tweaks и injector он
не устанавливает. Подсказки навигации на приборке сами по себе helper не требуют.

INSTALL.cmd запускает проверенный установщик и затем проверяет состояние.
Помощник берётся из APK, уже установленного на выбранном автомобиле. UID определяется
автоматически. Настройки сопряжения iPhone и данные VoyahPlay не переносятся
с другой машины. При повторной установке сохраняется выбор автозапуска.

Если программа просит перезагрузить мультимедиа, выполните обычную перезагрузку
и затем дважды нажмите STATUS.cmd. Перезагрузка автоматически не запускается.
STATUS.cmd только проверяет установленный помощник; повторно его не устанавливает.

Если автомобиль не виден — проверьте подключение ADB. При unauthorized подтвердите
отладку на мультимедиа. При ошибке версии сначала установите актуальный APK
VoyahPlay, затем повторите INSTALL.cmd. При ошибке совместимости сохраните журнал.

Журнал запуска: папка logs рядом с INSTALL.cmd.
Подробные журналы установщика: build\voyah-root-helper\<запуск>\report.txt.
При обращении за помощью приложите эти журналы. Они создаются после запуска.

В adb находятся оригинальные adb.exe, две DLL, лицензия NOTICE.txt и метаданные
Android SDK Platform-Tools. Остальные файлы SDK в комплект не копируются.
FILES-SHA256.json содержит контрольные суммы файлов поставки.
"@
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'), $readme, [Text.UTF8Encoding]::new($true))
$manifest = @(Get-ChildItem -LiteralPath $bundle -File -Recurse | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{
        file = $_.FullName.Substring($bundle.Length + 1).Replace('\','/')
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'), ($manifest | ConvertTo-Json -Depth 3), [Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle, $archive, [IO.Compression.CompressionLevel]::Optimal, $true)
Write-Output $archive
