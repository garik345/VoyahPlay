@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
title VoyahPlay - Fullscreen diagnostics
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-voyah-fullscreen.ps1"
set "VOYAH_COLLECTION_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %VOYAH_COLLECTION_EXIT%
