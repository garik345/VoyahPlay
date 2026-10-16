# Run once after extracting the source patch. Only exact retired paths are removed.
[CmdletBinding()]
param([string]$ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    # Resolve script-scoped paths in the body, not in a parameter default.
    $scriptDirectory = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptDirectory)) {
        $scriptFile = $MyInvocation.MyCommand.Path
        if (-not [string]::IsNullOrWhiteSpace($scriptFile)) {
            $scriptDirectory = [System.IO.Path]::GetDirectoryName($scriptFile)
        }
    }
    if ([string]::IsNullOrWhiteSpace($scriptDirectory)) {
        throw 'Cannot determine the script directory. Run with -File or specify -ProjectRoot.'
    }
    $ProjectRoot = Join-Path -Path $scriptDirectory -ChildPath '../..'
}
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $root 'common/src/main/assets/voyah-helper/runtime.sh'))) {
    throw 'Extract the new files into your VoyahPlay project first.'
}
$retired = @(
    'common/src/main/assets/voyah-root',
    'common/src/main/java/com/shilapi/xcertplay/voyah/root/RootModuleAssets.java',
    'common/src/test/java/com/shilapi/xcertplay/voyah/root/RootModuleAssetsTest.java',
    'scripts/tests/test_root_fullscreen.js',
    'scripts/tests/test_root_module_guards.py'
)
foreach ($relative in $retired) {
    $path = Join-Path $root $relative
    if (Test-Path -LiteralPath $path) {
        $entry = Get-Item -LiteralPath $path -Force
        if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing a link at retired path: $relative"
        }
        if ($entry.PSIsContainer) {
            $links = @(Get-ChildItem -LiteralPath $path -Recurse -Force | Where-Object {
                ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
            })
            if ($links.Count -gt 0) { throw "Refusing links inside retired directory: $relative" }
        }
        Remove-Item -LiteralPath $path -Recurse -Force
        Write-Host "Removed $relative"
    }
}
Write-Host 'Legacy sources removed. Build automotive in Android Studio, install the APK, then run root-helper.ps1 -Mode Install.'
