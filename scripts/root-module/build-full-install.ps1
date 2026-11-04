[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$AdbDirectory,
    [Parameter(Mandatory=$true)][string]$PrerequisiteDirectory,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$project=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$protocol=[IO.File]::ReadAllText((Join-Path $project 'shared/src/debug/java/com/shilapi/xcertplay/airplay/ultra/VoyahUltraProtocol.kt'))
$apkRevision=[regex]::Match($protocol, 'WIRE_REV\s*=\s*"(opack-[0-9]+-[a-z0-9-]+)"').Groups[1].Value
if(-not $apkRevision) { throw 'Cannot identify the current Ultra revision.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkArchive=[IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ApkPath).Path)
try {
    $revisionFound=$false
    foreach($entry in $apkArchive.Entries) {
        if($entry.FullName -notmatch '^classes[0-9]*\.dex$') { continue }
        $input=$entry.Open(); $buffer=[IO.MemoryStream]::new()
        try { $input.CopyTo($buffer); if([Text.Encoding]::UTF8.GetString($buffer.ToArray()).Contains($apkRevision)) { $revisionFound=$true; break } }
        finally { $input.Dispose(); $buffer.Dispose() }
    }
    if(-not $revisionFound) { throw 'APK revision differs from the current source. Supply the matching APK.' }
} finally { $apkArchive.Dispose() }
$bundle=Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ('VoyahPlay-'+$apkRevision+'-full-install')
$archive=$bundle+'-1.0.3-with-ADB.zip'
if((Test-Path -LiteralPath $bundle) -or (Test-Path -LiteralPath $archive)) { throw 'Choose a new output directory.' }
$bom=[Text.UTF8Encoding]::new($true); $plain=[Text.UTF8Encoding]::new($false)
foreach($folder in @('adb','bridge','prerequisites')) { [void][IO.Directory]::CreateDirectory((Join-Path $bundle $folder)) }
foreach($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt','source.properties')) {
    Copy-Item -LiteralPath (Join-Path $AdbDirectory $name) -Destination (Join-Path $bundle ('adb\'+$name))
}
foreach($name in @('start-full-install.ps1','root-helper.ps1')) {
    [IO.File]::WriteAllText((Join-Path $bundle $name),[IO.File]::ReadAllText((Join-Path $PSScriptRoot $name)),$bom)
}
foreach($pair in @(@('root-helper-bootstrap.sh','root-helper-bootstrap.sh'),@('pi-tweaks-bridge.sh','bridge-installer.sh'))) {
    [IO.File]::WriteAllText((Join-Path $bundle $pair[1]),[IO.File]::ReadAllText((Join-Path $PSScriptRoot $pair[0])).Replace("`r`n","`n"),$plain)
}
Copy-Item -LiteralPath $ApkPath -Destination (Join-Path $bundle 'VoyahPlay.apk')
$shutdown=[IO.File]::ReadAllText((Join-Path $project 'common/src/main/assets/voyah-helper/runtime.sh')).Replace("`r`n","`n")
[IO.File]::WriteAllText((Join-Path $bundle 'helper-quiesce.sh'),$shutdown,$plain)
$prerequisites=@{
    'VoyahTweaks.apk'='163aede713cda94fe9ffdb9c83e6af90ab02dc274b69b8466a0666de4eb07bd9'
    'frida-inject'='0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa'
    'QGWindowPolicy.apk'='4a9e271603f819a5e43da57bb6be5610c3a983b2d326f46ceb7b902d21972b37'
}
foreach($name in $prerequisites.Keys) {
    $source=Join-Path $PrerequisiteDirectory $name
    if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $prerequisites[$name]) { throw ('Unreviewed prerequisite '+$name) }
    Copy-Item -LiteralPath $source -Destination (Join-Path $bundle ('prerequisites\'+$name))
}
$bridge=Join-Path $PSScriptRoot 'pi-bridge\ss11.bin'
if((Get-FileHash -LiteralPath $bridge -Algorithm SHA256).Hash.ToLowerInvariant() -ne '575735ed6f6074f1c33483c8df5c8984e51e6dc0fba328b1e4179ca10b9f9d7d') { throw 'Unreviewed bridge' }
Copy-Item -LiteralPath $bridge -Destination (Join-Path $bundle 'bridge\ss11.bin')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pi-bridge\verification.json') -Destination (Join-Path $bundle 'bridge\verification.json')
foreach($name in @('start-voyah-compatibility.ps1','collect-voyah-compatibility.ps1')) {
    $source=Join-Path $project ('scripts\diagnostics\'+$name)
    [IO.File]::WriteAllText((Join-Path $bundle $name),[IO.File]::ReadAllText($source).Replace('START.cmd','COLLECT.cmd'),$bom)
}
$profiles=@(
    @{name='RU H97';bridgeProfile='RU';services='0faed7ebf1094c62a4f92b4bc533bbb3c08480a2cd847567cb1cff91b2d1d045';systemui='28f5d93eaec22b755b339193a733bd0d4160c496cf301bcd2fcfb88c36ade162'},
    @{name='PI OTA7.0 build86';bridgeProfile='PI';services='483fedea7707833454272f84a64692078996127d64abac63f13863b76246a579';systemui='5d5f7498d92ee68e927bc0b891daedf15978d079b85a254399112bad98d2dd6b'},
    @{name='PI OTA7.2 build35';bridgeProfile='PI';services='262bfd515f5c181a556477cadbdee1e574ead0ef1acff158dc3fa25bd8b84c38';systemui='b676a3a426ba3b59fabb9d98355ff0e0ac7b07d7ad6545ac44d9cbf85f1700c1'}
)
[IO.File]::WriteAllText((Join-Path $bundle 'profiles.json'),($profiles | ConvertTo-Json -Depth 4),$plain)
foreach($entry in @(@('INSTALL','-File "%~dp0start-full-install.ps1" -Mode Install'),@('STATUS','-File "%~dp0start-full-install.ps1" -Mode Status'),
    @('RESTORE_BRIDGE','-File "%~dp0start-full-install.ps1" -Mode RestoreBridge'),@('COLLECT','-File "%~dp0start-voyah-compatibility.ps1"'))) {
    $cmd="@echo off`r`nsetlocal DisableDelayedExpansion`r`nchcp 65001 >nul`r`n"
    $cmd+='"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass '+$entry[1]+"`r`n"
    $cmd+="set `"VOYAH_INSTALL_EXIT=%ERRORLEVEL%`"`r`necho.`r`npause`r`nexit /b %VOYAH_INSTALL_EXIT%`r`n"
    [IO.File]::WriteAllText((Join-Path $bundle ($entry[0]+'.cmd')),$cmd,[Text.Encoding]::ASCII)
}
$readme=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'full-install-README.txt')).Replace('__APK_REVISION__',$apkRevision)
[IO.File]::WriteAllText((Join-Path $bundle 'README.txt'),$readme,$bom)
[IO.File]::WriteAllText((Join-Path $bundle 'installer.json'),(@{revision='1.0.3';apkRevision=$apkRevision;helperVersion='1.2.16'} | ConvertTo-Json),$plain)
$manifest=@(Get-ChildItem -LiteralPath $bundle -Recurse -File | Sort-Object FullName | ForEach-Object {
    [PSCustomObject]@{file=$_.FullName.Substring($bundle.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[IO.File]::WriteAllText((Join-Path $bundle 'FILES-SHA256.json'),($manifest | ConvertTo-Json -Depth 3),$plain)
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($bundle,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
Write-Output $archive
