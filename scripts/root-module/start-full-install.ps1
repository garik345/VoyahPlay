# Windows PowerShell 5.1; the builder writes this file with a UTF-8 BOM.
[CmdletBinding()]
param([ValidateSet('Install','Status','RestoreBridge')][string]$Mode='Install', [string]$Serial)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference=$false }
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$script:AdbExe=Join-Path $PSScriptRoot 'adb\adb.exe'
$script:Target=@()
$transcribing=$false
$exitCode=1
function Install-Adb([string[]]$Arguments) {
    $saved=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $lines=@(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$saved }
    if ($code -ne 0) { throw ('ADB: ' + ($lines -join "`n")) }
    return ($lines -join "`n").Trim()
}
function File-Sha([string]$Path) {
    $stream=[IO.File]::OpenRead($Path); $digest=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
    finally { $digest.Dispose(); $stream.Dispose() }
}
function Bridge([string]$Operation,[string]$Profile) {
    $token=[guid]::NewGuid().ToString('N')
    $remoteScript='/data/local/tmp/voyah-bounds-'+$token+'.sh'
    $remotePayload='/data/local/tmp/voyah-bounds-'+$token+'.bin'
    try {
        Write-Host (Install-Adb @('push',(Join-Path $PSScriptRoot 'bridge-installer.sh'),$remoteScript))
        if ($Operation -eq 'install') { Write-Host (Install-Adb @('push',(Join-Path $PSScriptRoot 'bridge\ss11.bin'),$remotePayload)) }
        Write-Host (Install-Adb @('shell','su','0','sh',$remoteScript,$Operation,$remotePayload,$Profile))
    } finally {
        try { [void](Install-Adb @('shell','su','0','rm','-f',$remoteScript,$remotePayload)) } catch { Write-Warning $_.Exception.Message }
    }
}
try {
    [void][IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot 'logs'))
    $log=Join-Path $PSScriptRoot ('logs\'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)+'.txt')
    Start-Transcript -LiteralPath $log -Force | Out-Null
    $transcribing=$true
    Write-Host 'VoyahPlay — полный установочный комплект RU / ПИ' -ForegroundColor Cyan
    $manifest=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'FILES-SHA256.json') -Raw | ConvertFrom-Json
    $names=@($manifest | ForEach-Object {$_.file})
    foreach($required in @('VoyahPlay.apk','start-full-install.ps1','root-helper.ps1','root-helper-bootstrap.sh','helper-quiesce.sh',
        'bridge-installer.sh','bridge/ss11.bin','prerequisites/VoyahTweaks.apk','prerequisites/frida-inject',
        'prerequisites/QGWindowPolicy.apk','adb/adb.exe','adb/AdbWinApi.dll','adb/AdbWinUsbApi.dll',
        'INSTALL.cmd','STATUS.cmd','RESTORE_BRIDGE.cmd','COLLECT.cmd','profiles.json')) {
        if (@($names | Where-Object {$_ -ceq $required}).Count -ne 1) { throw ('Неполный комплект: '+$required) }
    }
    foreach($entry in $manifest) {
        if($entry.file -notmatch '^[A-Za-z0-9_./-]+$' -or $entry.file -match '(^|/)\.\.(/|$)' -or $entry.file.StartsWith('/') -or
           $entry.sha256 -notmatch '^[a-f0-9]{64}$') { throw 'Некорректный манифест.' }
        if((File-Sha (Join-Path $PSScriptRoot $entry.file)) -ne $entry.sha256) { throw ('Повреждён файл '+$entry.file+'. Распакуйте весь архив заново.') }
    }
    $devices=@((Install-Adb @('devices','-l')) -split "`r?`n" | ForEach-Object {
        if($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)(.*)$') { [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2];Details=$Matches[3]} }
    })
    if($devices.Count -eq 0) { throw 'Автомобиль не найден. Подключите ADB и повторите запуск.' }
    if($Serial) {
        $selection=@($devices | Where-Object {$_.Serial -eq $Serial})
        if($selection.Count -ne 1) { throw 'Указанный serial не найден.' }; $chosen=$selection[0]
    } elseif($devices.Count -eq 1) { $chosen=$devices[0] }
    else {
        for($i=0;$i -lt $devices.Count;$i++) { Write-Host ('{0}. {1} [{2}] {3}' -f ($i+1),$devices[$i].Serial,$devices[$i].State,$devices[$i].Details) }
        $number=0
        do { $answer=Read-Host 'Номер автомобиля (0 — выход)'; if($answer -eq '0'){throw [OperationCanceledException]::new('Отменено.')}
        } until([int]::TryParse($answer,[ref]$number) -and $number -gt 0 -and $number -le $devices.Count)
        $chosen=$devices[$number-1]
    }
    if($chosen.State -ne 'device') { throw ('Устройство '+$chosen.State+'. Подтвердите отладку или восстановите ADB.') }
    $script:Target=@('-s',$chosen.Serial)
    Write-Host ('Выбран автомобиль: '+$chosen.Serial)
    if((Install-Adb @('shell','su','0','id')) -notmatch '^uid=0(?:\(|\s)') { throw 'Нужен действующий root (su 0). Комплект не получает root.' }
    if((Install-Adb @('shell','getprop','ro.build.version.sdk')) -ne '30') { throw 'Для этого комплекта требуется проверенная прошивка Android 11.' }
    $services=(Install-Adb @('shell','su','0','sha256sum','/system/framework/services.jar')).Split(' ')[0]
    $ui=(Install-Adb @('shell','su','0','sha256sum','/system/priv-app/SystemUI-release-signed/SystemUI-release-signed.apk')).Split(' ')[0]
    $profiles=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'profiles.json') -Raw | ConvertFrom-Json
    $matched=@($profiles | Where-Object {$_.services -ceq $services -and $_.systemui -ceq $ui})
    if($matched.Count -ne 1) { throw 'Прошивка не изучена. Запустите COLLECT.cmd и передайте полученный отчёт. Системные файлы другой машины не устанавливались.' }
    $profile=$matched[0]
    Write-Host ('Прошивка: '+$profile.name)
    if($Mode -eq 'RestoreBridge') { Bridge 'restore' $profile.bridgeProfile; $exitCode=0 }
    elseif($Mode -eq 'Status') {
        & (Join-Path $PSScriptRoot 'root-helper.ps1') -Mode Status -ProjectRoot $PSScriptRoot -Adb $script:AdbExe -Serial $chosen.Serial
        Bridge 'status' $profile.bridgeProfile
        Write-Host 'В APK: Диагностика → Root-помощник → Проверить расширение окна. До загрузки моста после перезагрузки функции заблокированы.'
        $exitCode=0
    } else {
        $tweaks=Install-Adb @('shell','pm','list','packages','ru.kachalin.voyahtweaks')
        if($tweaks -notmatch '(?m)^package:ru\.kachalin\.voyahtweaks\r?$') {
            Write-Host (Install-Adb @('install','-r',(Join-Path $PSScriptRoot 'prerequisites\VoyahTweaks.apk')))
            Write-Host (Install-Adb @('shell','monkey','-p','ru.kachalin.voyahtweaks','-c','android.intent.category.LAUNCHER','1'))
            throw [OperationCanceledException]::new('VoyahTweaks установлен. Выполните его штатную активацию и подготовку системы, затем снова запустите INSTALL.cmd. Активация из другой машины не переносится.')
        }
        # Tweaks prepares its own boot hook and system components. Never replace OEM APK/JAR files.
        # The following check also verifies injector, original/owned bridge and its backup before APK installation.
        Bridge 'check' $profile.bridgeProfile
        Write-Host (Install-Adb @('install','-r',(Join-Path $PSScriptRoot 'VoyahPlay.apk')))
        Bridge 'install' $profile.bridgeProfile
        & (Join-Path $PSScriptRoot 'root-helper.ps1') -Mode Install -ProjectRoot $PSScriptRoot -Adb $script:AdbExe -Serial $chosen.Serial
        & (Join-Path $PSScriptRoot 'root-helper.ps1') -Mode EnableAutoStart -ProjectRoot $PSScriptRoot -Adb $script:AdbExe -Serial $chosen.Serial
        & (Join-Path $PSScriptRoot 'root-helper.ps1') -Mode Status -ProjectRoot $PSScriptRoot -Adb $script:AdbExe -Serial $chosen.Serial
        Write-Host 'Установка завершена. Перезагрузите мультимедиа обычным способом, затем запустите STATUS.cmd.' -ForegroundColor Green
        Write-Host 'В VoyahPlay включите нужные функции. Изменения Ultra, радио и карты применяются при следующем подключении iPhone.'
        $exitCode=0
    }
} catch [OperationCanceledException] { Write-Host $_.Exception.Message -ForegroundColor Yellow; $exitCode=2
} catch { Write-Host ('Операция остановлена: '+$_.Exception.Message) -ForegroundColor Red
    Write-Host 'Сохраните журнал из logs. Для другой прошивки используйте COLLECT.cmd.'
} finally { if($transcribing) { Stop-Transcript | Out-Null } }
exit $exitCode
