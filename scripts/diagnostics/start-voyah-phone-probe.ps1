[CmdletBinding()]
param([ValidateSet('Install','OpenRecents','WheelTest','Stop')][string]$Mode='OpenRecents', [string]$Serial)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) { $PSNativeCommandUseErrorActionPreference=$false }
[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
$adb=Join-Path $PSScriptRoot 'adb/adb.exe'
$apk=Join-Path $PSScriptRoot 'VoyahPlay-opack-100-phone-probe-receiver-debug.apk'
$script:target=@(); $script:evidenceFile=$null
$utf8=[Text.UTF8Encoding]::new($true)
function Invoke-Adb([string[]]$Arguments) {
    $saved=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        $lines=@(& $adb @script:target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$saved }
    if ($code -ne 0) { throw ($lines -join "`n") }
    return ($lines -join "`n")
}
function Save-Evidence([string]$Stage,[string]$Text) {
    if ($script:evidenceFile) {
        [IO.File]::AppendAllText($script:evidenceFile,("`n[$Stage]`n$Text`n"),[Text.UTF8Encoding]::new($false))
    }
}
function Send-Probe([string]$TestMode,[string]$Id) {
    try {
        $answer=Invoke-Adb @('shell','am','broadcast','--receiver-foreground','-n',
            'com.shilapi.xcertplay/com.shilapi.xcertplay.VoyahPhoneKeyProbeReceiver',
            '--es','mode',$TestMode,'--es','request_id',$Id)
    } catch { Save-Evidence ("BROADCAST $TestMode $Id") $_.Exception.Message; throw }
    Save-Evidence ("BROADCAST $TestMode $Id") $answer
    Write-Host $answer
    if ($answer -match '(?im)^Error:|Permission Denial|SecurityException') { throw $answer }
    $ack='data="VP_PHONE_PROBE:'+[regex]::Escape($Id)+':accepted"(?:,|\s|$)'
    if ($answer -notmatch '(?m)^\s*Broadcast completed:\s*result=200(?:,|\s|$)' -or $answer -notmatch $ack) {
        throw 'Нет подтверждения получения именно этой команды. Проверьте установку opack-100; результат на экране пока не оцениваем.'
    }
}
function Read-Journal([string]$Id) {
    try { $raw=Invoke-Adb @('exec-out','run-as','com.shilapi.xcertplay','cat',('files/phone-probe/'+$Id+'.jsonl')) }
    catch { Save-Evidence ("JOURNAL_READ_ERROR $Id") $_.Exception.Message; return }
    if ($raw.Length -gt 524288) { throw 'Журнал превышает ожидаемый размер.' }
    Save-Evidence ("JOURNAL $Id") $raw
    foreach ($line in $raw -split "`r?`n") {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try { $row=$line | ConvertFrom-Json -ErrorAction Stop } catch { continue }
        $owner=$row.PSObject.Properties['requestId']; $event=$row.PSObject.Properties['event']; $detail=$row.PSObject.Properties['detail']
        if ($owner -and $event -and $detail -and [string]$owner.Value -ceq $Id) { $row }
    }
}
function Wait-Event([string]$Id,[string]$Event) {
    $clock=[Diagnostics.Stopwatch]::StartNew()
    do {
        $rows=@(Read-Journal $Id)
        $errors=@($rows | Where-Object {$_.event -eq 'ERROR'})
        if ($errors.Count -gt 0) { throw ('Приложение отклонило тест: '+$errors[0].detail) }
        $entries=@($rows | Where-Object {$_.event -eq 'ENTRY'})
        $matches=@($rows | Where-Object {$_.event -eq $Event})
        if ($entries.Count -gt 0 -and $matches.Count -gt 0) {
            if ($Event -eq 'RECENTS_DELIVERED' -and $matches[-1].detail -cne 'true') { throw 'Приложение получило команду, но отправка iPhone не состоялась.' }
            return
        }
        Start-Sleep -Milliseconds 400
    } while ($clock.Elapsed.TotalSeconds -lt 8)
    throw ('В журнале этой команды нет подтверждения '+$Event+'. Нужен сохранённый отчёт; это ещё не результат проверки интерфейса iPhone.')
}
$stopNeeded=$false; $requestId=$null
try {
    foreach ($file in @($adb,$apk,(Join-Path $PSScriptRoot 'adb/AdbWinApi.dll'),(Join-Path $PSScriptRoot 'adb/AdbWinUsbApi.dll'))) {
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Распакуйте архив целиком: не хватает файлов комплекта.' }
    }
    $devices=@((Invoke-Adb @('devices','-l')) -split "`r?`n" | ForEach-Object {
        if ($_ -match '^(\S+)\s+(device|offline|unauthorized)(?:\s|$)') { [PSCustomObject]@{Serial=$Matches[1];State=$Matches[2]} }
    })
    if ($Serial) { $chosen=@($devices | Where-Object { $_.Serial -eq $Serial }) }
    elseif ($devices.Count -eq 1) { $chosen=@($devices[0]) }
    elseif ($devices.Count -gt 1) {
        for ($i=0;$i -lt $devices.Count;$i++) { Write-Host ("{0}. {1} [{2}]" -f ($i+1),$devices[$i].Serial,$devices[$i].State) }
        $answer=Read-Host 'Номер автомобиля (0 — отмена)'; $number=0
        if (-not [int]::TryParse($answer,[ref]$number) -or $number -lt 1 -or $number -gt $devices.Count) { throw 'Устройство не выбрано.' }
        $chosen=@($devices[$number-1])
    } else { throw 'ADB не видит автомобиль. Проверьте подключение и разрешение отладки.' }
    if ($chosen.Count -ne 1 -or $chosen[0].State -ne 'device' -or $chosen[0].Serial -notmatch '^[A-Za-z0-9._:\[\]-]+$') { throw 'Выбранное устройство отсутствует, offline или unauthorized.' }
    $script:target=@('-s',$chosen[0].Serial)
    Write-Host ('Автомобиль: '+$chosen[0].Serial) -ForegroundColor Cyan
    if ($Mode -eq 'Install') {
        Write-Host 'Установка тестовой opack-100. После обновления дождитесь подключения CarPlay.'
        Write-Host (Invoke-Adb @('install','-r',$apk))
        Write-Host 'Теперь оставьте CarPlay на экране и запустите OPEN_RECENTS.cmd.' -ForegroundColor Green
        exit 0
    }
    $requestId=[guid]::NewGuid().ToString('N')
    $folder=Join-Path $PSScriptRoot 'logs'; [void][IO.Directory]::CreateDirectory($folder)
    $script:evidenceFile=Join-Path $folder ((Get-Date -Format 'yyyyMMdd-HHmmss-fff')+'-'+$Mode+'-'+$requestId.Substring(0,8)+'.txt')
    [IO.File]::WriteAllText($script:evidenceFile,("OPACK=100`nMODE=$Mode`nREQUEST_ID=$requestId`nDEVICE=$($chosen[0].Serial)`n"),$utf8)
    $testMode=switch($Mode) { 'OpenRecents' {'open-recents'} 'WheelTest' {'phone-key-30s'} 'Stop' {'stop'} }
    if ($Mode -eq 'WheelTest') { $stopNeeded=$true }
    Send-Probe $testMode $requestId
    switch ($Mode) {
        'OpenRecents' {
            Wait-Event $requestId 'RECENTS_DELIVERED'
            Start-Sleep -Seconds 2
            [void]@(Read-Journal $requestId)
            Write-Host 'Команда получена приложением и отправлена iPhone. Что открылось именно на экране CarPlay?' -ForegroundColor Green
        }
        'WheelTest' {
            Wait-Event $requestId 'READY'
            Write-Host 'Тест готов. В течение 30 секунд без активного звонка нажмите трубку ОДИН раз.' -ForegroundColor Cyan
            Start-Sleep -Seconds 35
            Wait-Event $requestId 'STOPPED'
            $stopNeeded=$false
            Write-Host 'Временный обработчик остановлен. Проверьте, вернулось ли штатное действие трубки.' -ForegroundColor Green
        }
        'Stop' { Wait-Event $requestId 'STOPPED'; Write-Host 'Тест остановлен.' -ForegroundColor Green }
    }
    Save-Evidence 'RESULT' 'command-and-journal-confirmed; visual-result-required'
    Write-Host ('Отчёт: '+$script:evidenceFile)
    Write-Host 'Отправка команды не доказывает поддержку адреса «Недавних» самим iPhone. Нужен результат на экране.' -ForegroundColor Yellow
} catch {
    $failureMessage=$_.Exception.Message
    Save-Evidence 'ERROR' $failureMessage
    if ($requestId) { try { [void]@(Read-Journal $requestId) } catch { Save-Evidence 'FINAL_JOURNAL_ERROR' $_.Exception.Message } }
    if ($stopNeeded) {
        try { $stopId=[guid]::NewGuid().ToString('N'); Send-Probe 'stop' $stopId; Wait-Event $stopId 'STOPPED' }
        catch { Save-Evidence 'STOP_ERROR' $_.Exception.Message }
    }
    Write-Host ('Ошибка: '+$failureMessage) -ForegroundColor Red
    if ($script:evidenceFile) { Write-Host ('Отчёт сохранён: '+$script:evidenceFile) }
    exit 1
}
