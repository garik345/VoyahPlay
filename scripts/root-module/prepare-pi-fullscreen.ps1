# Invoked by the portable launcher after it selects one authorized ADB device.
[CmdletBinding()]
param(
    [ValidateSet('Check','Install','Restore')][string]$Mode = 'Check',
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Serial
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$target = @('-s', $Serial)
function Pi-Sha256([string]$Path) {
    $inputStream = [IO.File]::OpenRead($Path)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($digest.ComputeHash($inputStream))).Replace('-','').ToLowerInvariant() }
    finally { $digest.Dispose(); $inputStream.Dispose() }
}
function Pi-Adb([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $Adb @target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    foreach ($line in $lines) { Write-Host $line }
    if ($code -ne 0) { throw "PI preparation failed (ADB $code). See the log above." }
}
$payload = Join-Path $PSScriptRoot 'pi-bridge\ss11.bin'
$deviceScript = Join-Path $PSScriptRoot 'pi-tweaks-bridge.sh'
if ((Pi-Sha256 $payload) -ne
    '575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d') { throw 'Bridge payload checksum mismatch.' }
if ($Mode -ne 'Restore') {
    $apk = Join-Path $PSScriptRoot 'VoyahPlay.apk'
    $manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pi-package.json') -Raw | ConvertFrom-Json
    if ($manifest.apkSha256 -notmatch '^[a-f0-9]{64}$' -or
        (Pi-Sha256 $apk) -ne $manifest.apkSha256) {
        throw 'APK checksum mismatch. Extract the complete package again.'
    }
}
$token = [guid]::NewGuid().ToString('N')
$remoteScript = '/data/local/tmp/voyah-bounds-' + $token + '.sh'
$remotePayload = '/data/local/tmp/voyah-bounds-' + $token + '.bin'
try {
    # The shipped shell source uses LF; push preserves bytes on Windows PowerShell 5.1.
    Pi-Adb @('push', $deviceScript, $remoteScript)
    Pi-Adb @('shell','su','0','sh', $remoteScript,'check')
    if ($Mode -eq 'Install') {
        Pi-Adb @('install','-r',$apk)
        Pi-Adb @('push',$payload,$remotePayload)
        Pi-Adb @('shell','su','0','sh',$remoteScript,'install',$remotePayload)
        Write-Host 'Подготовка ПИ завершена. После установки помощника перезагрузите мультимедиа обычным способом.' -ForegroundColor Yellow
    } elseif ($Mode -eq 'Restore') {
        Pi-Adb @('shell','su','0','sh',$remoteScript,'restore')
        Write-Host 'Исходный модуль восстановлен. Перезагрузите мультимедиа обычным способом.' -ForegroundColor Yellow
    }
} finally {
    # Only this invocation's two fixed UUID paths are removed.
    try { Pi-Adb @('shell','su','0','rm','-f',$remoteScript,$remotePayload) } catch { Write-Warning $_.Exception.Message }
}
