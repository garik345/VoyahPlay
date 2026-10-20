param([string]$OutputDirectory = '')

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$voyahAdb = (Get-Command adb -ErrorAction Stop).Source
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $voyahProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $OutputDirectory = Join-Path $voyahProjectRoot 'build/voyah-climate-api'
}
$voyahStamp = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$voyahCapture = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $voyahStamp
[IO.Directory]::CreateDirectory($voyahCapture) | Out-Null
$voyahReport = Join-Path $voyahCapture 'report.txt'
$voyahUtf8 = New-Object Text.UTF8Encoding($false)
$script:voyahAdbCode = 0

function Invoke-VoyahRead {
    param([string[]]$AdbArguments)
    $voyahOldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $voyahLines = @(& $voyahAdb @AdbArguments 2>&1 | ForEach-Object { $_.ToString() })
        $script:voyahAdbCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $voyahOldPreference }
    [IO.File]::AppendAllText($voyahReport, ('ADB ' + ($AdbArguments -join ' ') + "`r`n" + ($voyahLines -join "`r`n") + "`r`nEXIT=$script:voyahAdbCode`r`n"), $voyahUtf8)
    return $voyahLines
}

$voyahState = @(Invoke-VoyahRead -AdbArguments @('get-state'))
if ($script:voyahAdbCode -ne 0 -or ($voyahState -join '').Trim() -ne 'device') {
    throw "One authorized ADB device is required. Report: $voyahReport"
}
Invoke-VoyahRead -AdbArguments @('shell','getprop','ro.build.fingerprint') | Out-Null
Invoke-VoyahRead -AdbArguments @('shell','getprop','ro.build.version.release') | Out-Null

# Copy public OEM interfaces and their implementations. No writes or root commands on the device.
Invoke-VoyahRead -AdbArguments @('pull','/system/framework/QGAPI.jar',(Join-Path $voyahCapture 'QGAPI.jar')) | Out-Null
Invoke-VoyahRead -AdbArguments @('pull','/system/framework/QGVehicle.jar',(Join-Path $voyahCapture 'QGVehicle.jar')) | Out-Null
foreach ($voyahPackage in @('com.qinggan.canbus.service','com.qinggan.carsignal.service','com.qinggan.app.vehicle')) {
    $voyahPaths = @(Invoke-VoyahRead -AdbArguments @('shell','pm','path',$voyahPackage))
    $voyahIndex = 0
    foreach ($voyahPathLine in $voyahPaths) {
        if ($voyahPathLine -match '^package:(/.+\.apk)$') {
            $voyahRemotePath = $Matches[1]
            $voyahName = $voyahPackage + '-' + $voyahIndex + '.apk'
            Invoke-VoyahRead -AdbArguments @('pull',$voyahRemotePath,(Join-Path $voyahCapture $voyahName)) | Out-Null
            $voyahIndex++
        }
    }
}
$voyahHashes = @(Get-ChildItem -LiteralPath $voyahCapture -File | Where-Object { $_.Extension -in @('.jar','.apk') } | ForEach-Object {
    $voyahHash = Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256
    [PSCustomObject]@{ file = $_.Name; bytes = $_.Length; sha256 = $voyahHash.Hash.ToLowerInvariant() }
})
[IO.File]::WriteAllText((Join-Path $voyahCapture 'SHA256.json'), (ConvertTo-Json -InputObject $voyahHashes -Depth 4), $voyahUtf8)
$voyahRequired = @('QGAPI.jar','QGVehicle.jar','com.qinggan.canbus.service-0.apk','com.qinggan.app.vehicle-0.apk')
$voyahMissing = @($voyahRequired | Where-Object { -not (Test-Path -LiteralPath (Join-Path $voyahCapture $_) -PathType Leaf) })
if ($voyahMissing.Count -gt 0) {
    [IO.File]::AppendAllText($voyahReport, ('CAPTURE_INCOMPLETE: ' + ($voyahMissing -join ', ') + "`r`n"), $voyahUtf8)
}
$voyahZip = $voyahCapture + '.zip'
Compress-Archive -LiteralPath $voyahCapture -DestinationPath $voyahZip
Write-Host "Report ZIP: $voyahZip"
if ($voyahMissing.Count -gt 0) { Write-Warning ('Partial capture. Missing: ' + ($voyahMissing -join ', ') + '. Send the ZIP with report.txt.') }
Write-Host 'Send this ZIP to inspect the exact HVAC methods. No device settings were changed.'
