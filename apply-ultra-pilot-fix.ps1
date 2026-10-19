# Compatibility entry point. All work and checks are in apply-ultra-pilot.ps1.
[CmdletBinding()]
param([string]$ProjectRoot, [switch]$DryRun)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = $PSScriptRoot }
$voyahUltraInstaller = Join-Path $PSScriptRoot 'apply-ultra-pilot.ps1'
& $voyahUltraInstaller -ProjectRoot $ProjectRoot -DryRun:$DryRun
