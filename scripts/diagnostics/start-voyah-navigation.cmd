@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
title VoyahPlay - Navigation diagnostics
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-voyah-navigation.ps1"
set "VOYAH_COLLECTION_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %VOYAH_COLLECTION_EXIT%
