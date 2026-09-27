@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Ops Surface Thin
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\demo1_ops_surface_thin.ps1" %*
set "OST_EXIT=%ERRORLEVEL%"
echo.
if "%~1"=="" echo [INFO] WhatIf mode (report only). Run Ops-Surface-Thin.bat -Apply to actually move closed directives/targets to agent-prompts\_archive.
if not "%OST_EXIT%"=="0" echo [FAILED] ops surface thin exit code: %OST_EXIT%. See var\debug\ops-surface-thin-*.json.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %OST_EXIT%
