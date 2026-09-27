@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Vibe Max Agency
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\demo1_vibe_max_agency.ps1" %*
set "VMA_EXIT=%ERRORLEVEL%"
echo.
if "%~1"=="" echo [INFO] Check mode (report only). Run Vibe-Max-Agency.bat -Apply to apply relaxations.
if not "%VMA_EXIT%"=="0" echo [FAILED] vibe-max-agency exit code: %VMA_EXIT%. See var\debug\vibe-max-agency-status.json.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %VMA_EXIT%
