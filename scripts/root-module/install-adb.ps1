# PowerShell 5.1+; install files and read status only. Never enables the hooks.
[CmdletBinding()]
param(
    [ValidateSet('Install', 'Status')][string]$Mode = 'Install',
    [string]$ProjectRoot,
    [string]$Serial,
    [string]$Adb = 'adb'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

function Find-VoyahProject([string]$Start) {
    $directory = [System.IO.DirectoryInfo]::new($Start)
    while ($null -ne $directory) {
        if (Test-Path -LiteralPath (Join-Path $directory.FullName 'common/src/main/assets/voyah-root/module.sh') -PathType Leaf) {
            return $directory.FullName
        }
        $directory = $directory.Parent
    }
    return $null
}

if (-not $ProjectRoot) {
    $ProjectRoot = Find-VoyahProject $PSScriptRoot
    if (-not $ProjectRoot) { $ProjectRoot = Find-VoyahProject (Get-Location).Path }
}
if (-not $ProjectRoot) { throw 'VoyahPlay sources not found. Use -ProjectRoot D:\voyah\carplay\VoyahPlay.' }
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$assets = Join-Path $ProjectRoot 'common/src/main/assets/voyah-root'
$script:AdbExe = (Get-Command $Adb -CommandType Application -ErrorAction Stop).Source
$script:Target = @()
if ($Serial) { $script:Target = @('-s', $Serial) }
$runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N')
$reportDirectory = Join-Path $ProjectRoot ('build/voyah-root-install/' + $runName)
[void][System.IO.Directory]::CreateDirectory($reportDirectory)
$script:Report = Join-Path $reportDirectory 'report.txt'
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText($script:Report, "VoyahPlay ADB installer 1.0`nMode: $Mode`nTime: $(Get-Date -Format o)`n", $utf8)

function Write-Report([string]$Text) {
    Write-Host $Text
    [System.IO.File]::AppendAllText($script:Report, $Text + "`n", [System.Text.UTF8Encoding]::new($false))
}

function Invoke-VoyahAdb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    Write-Report ('ADB ' + ($Arguments -join ' '))
    $savedPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 represents redirected native stderr as ErrorRecord.
        # Judge native commands by their exit code, including successful adb progress.
        $ErrorActionPreference = 'Continue'
        $text = @(& $script:AdbExe @script:Target @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $savedPreference }
    foreach ($line in $text) { Write-Report $line }
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB failed with exit code $code. See $script:Report" }
    return ($text -join "`n")
}

$localStage = $null
$remote = $null
$remoteCreated = $false
try {
    [void](Invoke-VoyahAdb -Arguments @('get-state'))
    $rootIdentity = Invoke-VoyahAdb -Arguments @('shell', 'su', '0', 'id')
    if ($rootIdentity -notmatch '(?m)^uid=0(?:\(|\s|$)') { throw 'Root through ADB is not confirmed.' }
    [void](Invoke-VoyahAdb -Arguments @('shell', 'getenforce'))
    $statusCommand = 'if [ -f /data/local/voyahplay-fullscreen/payload/module.sh ]; then sh /data/local/voyahplay-fullscreen/payload/module.sh status; else echo NOT_INSTALLED; fi'
    $before = Invoke-VoyahAdb -Arguments @('shell', 'su', '0', 'sh', '-c', "'$statusCommand'")
    if ($Mode -eq 'Status') {
        Write-Report 'DONE: read-only module status. No installation or hooks started.'
        return
    }
    if ($before -match '(?m)^ENABLED=1\s*$') {
        throw 'Module is enabled. This installer will not update an active module. Read status and disable it first.'
    }
    $package = Invoke-VoyahAdb -Arguments @('shell', 'pm', 'path', '--user', '0', 'com.shilapi.xcertplay')
    if ($package -notmatch '(?m)^package:.*\.apk\s*$') { throw 'Install the automotive APK for user 0 first.' }

    $localStage = Join-Path $reportDirectory 'transfer'
    [void][System.IO.Directory]::CreateDirectory($localStage)
    foreach ($name in @('module.sh', 'system-server.js', 'launcher.js')) {
        $source = Join-Path $assets $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Missing source asset: $source" }
        # Git on Windows may check out CRLF. Android scripts must use LF and no BOM.
        $content = [System.IO.File]::ReadAllText($source).Replace("`r`n", "`n").Replace("`r", "`n")
        [System.IO.File]::WriteAllText((Join-Path $localStage $name), $content, $utf8)
    }
    $moduleText = [System.IO.File]::ReadAllText((Join-Path $localStage 'module.sh'))
    $hashMatch = [regex]::Match($moduleText, '(?m)^BIN_HASH=([a-f0-9]{64})$')
    if (-not $hashMatch.Success) { throw 'Cannot read the binary checksum from module.sh.' }
    $binaryPath = Join-Path $localStage 'frida-inject'
    $input = $null; $gzip = $null; $output = $null
    try {
        $input = [System.IO.File]::OpenRead((Join-Path $assets 'frida-inject-arm64.gz'))
        $gzip = [System.IO.Compression.GZipStream]::new($input, [System.IO.Compression.CompressionMode]::Decompress)
        $output = [System.IO.File]::Create($binaryPath)
        $buffer = [byte[]]::new(65536)
        $total = 0L
        while (($count = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $total += $count
            if ($total -gt 67108864) { throw 'Uncompressed component exceeds 64 MiB.' }
            $output.Write($buffer, 0, $count)
        }
    }
    finally {
        if ($null -ne $output) { $output.Dispose() }
        if ($null -ne $gzip) { $gzip.Dispose() }
        if ($null -ne $input) { $input.Dispose() }
    }
    if ((Get-FileHash -LiteralPath $binaryPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hashMatch.Groups[1].Value) {
        throw 'Binary checksum mismatch. Nothing was installed.'
    }
    $fileNames = @('module.sh', 'system-server.js', 'launcher.js', 'frida-inject')
    $manifest = foreach ($name in $fileNames) {
        (Get-FileHash -LiteralPath (Join-Path $localStage $name) -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $name
    }
    [System.IO.File]::WriteAllText((Join-Path $localStage 'SHA256SUMS'), ($manifest -join "`n") + "`n", $utf8)

    $nonce = [guid]::NewGuid().ToString('N')
    $remote = '/data/local/tmp/voyahplay-transfer-' + $nonce
    $appStage = '/data/user/0/com.shilapi.xcertplay/cache/voyah-root-adb-' + $nonce
    $bootstrap = @'
#!/system/bin/sh
set -eu
source=$1
stage=$2
case "$source" in /data/local/tmp/voyahplay-transfer-*) ;; *) exit 2;; esac
case "$stage" in /data/user/0/com.shilapi.xcertplay/cache/voyah-root-adb-*) ;; *) exit 2;; esac
[ "$(id -u)" = 0 ] || exit 2
[ ! -L "$source" ] && [ -d "$source" ] || exit 2
[ ! -L /data/user/0/com.shilapi.xcertplay/cache ] && [ -d /data/user/0/com.shilapi.xcertplay/cache ] || { echo 'ERROR APK cache missing; open VoyahPlay once'; exit 2; }
[ ! -e "$stage" ] && [ ! -L "$stage" ] || exit 2
mkdir "$stage"
chmod 700 "$stage"
cleanup() { rm -f "$stage/module.sh" "$stage/system-server.js" "$stage/launcher.js" "$stage/frida-inject" "$stage/SHA256SUMS"; rmdir "$stage"; }
trap cleanup EXIT
for name in module.sh system-server.js launcher.js frida-inject SHA256SUMS; do
    [ -f "$source/$name" ] && [ ! -L "$source/$name" ] || exit 2
    cp "$source/$name" "$stage/$name"
done
cd "$stage"
sha256sum -c SHA256SUMS
sh "$stage/module.sh" install "$stage"
sh /data/local/voyahplay-fullscreen/payload/module.sh status
'@
    [System.IO.File]::WriteAllText((Join-Path $localStage 'bootstrap.sh'), $bootstrap.Replace("`r`n", "`n") + "`n", $utf8)
    [void](Invoke-VoyahAdb -Arguments @('shell', 'mkdir', '-m', '700', $remote))
    $remoteCreated = $true
    foreach ($name in ($fileNames + @('SHA256SUMS', 'bootstrap.sh'))) {
        [void](Invoke-VoyahAdb -Arguments @('push', (Join-Path $localStage $name), ($remote + '/' + $name)))
    }
    $result = Invoke-VoyahAdb -Arguments @('shell', 'su', '0', 'sh', ($remote + '/bootstrap.sh'), $remote, $appStage)
    if ($result -notmatch '(?m)^READY installed ' -or $result -notmatch '(?m)^ENABLED=0\s*$') {
        throw 'Installation or disabled state was not confirmed. Send report.txt; do not enable hooks.'
    }
    Write-Report 'DONE: module files installed and ENABLED=0 confirmed. No injector or loader was started.'
    Write-Report 'APK root access is still unavailable; this step does not install the APK command helper.'
}
catch {
    Write-Report ('ERROR: ' + $_.Exception.Message)
    throw
}
finally {
    if ($remoteCreated) {
        try { [void](Invoke-VoyahAdb -Arguments @('shell', 'rm', '-r', $remote) -AllowFailure) }
        catch { Write-Report ('Temporary remote cleanup failed: ' + $_.Exception.Message) }
    }
    if ($localStage -and (Test-Path -LiteralPath $localStage)) {
        try { Remove-Item -LiteralPath $localStage -Recurse -Force }
        catch { Write-Report ('Temporary local cleanup failed: ' + $_.Exception.Message) }
    }
    Write-Host "Report: $script:Report"
}
