@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Safe Cleanup
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\demo1_safe_cleanup.ps1" %*
set "CLN_EXIT=%ERRORLEVEL%"
echo.
if "%~1"=="" echo [INFO] WhatIf mode (report only). Run Safe-Cleanup.bat -Apply after approval to actually delete.
if not "%CLN_EXIT%"=="0" echo [FAILED] safe cleanup exit code: %CLN_EXIT%. See var\debug\safe-cleanup-*.json.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %CLN_EXIT%
