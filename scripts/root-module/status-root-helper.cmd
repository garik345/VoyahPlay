@echo off
setlocal DisableDelayedExpansion
chcp 65001 >nul
title VoyahPlay - Root helper status
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-root-helper.ps1" -Mode Status
set "VOYAH_HELPER_EXIT=%ERRORLEVEL%"
echo.
pause
exit /b %VOYAH_HELPER_EXIT%
