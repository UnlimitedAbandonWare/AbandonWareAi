@echo off
setlocal
cd /d "%~dp0"
title AbandonWare Meta Display Debug (wear)
set "DISPLAY_DBG_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
"%DISPLAY_DBG_PS%" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\debug_rag_stack.ps1" -Role wear %*
set "DISPLAY_DBG_EXIT=%ERRORLEVEL%"
echo.
if not "%DISPLAY_DBG_EXIT%"=="0" echo [RESULT] Meta Display debug exit code: %DISPLAY_DBG_EXIT% ^(0=ready/verified, 2=blocked, 3=not-running, 4=degraded, 5=verbose-not-applied, 6=verify-checks-failed, 1=failed^). See [DBG ...] output above and var\debug\ logs.
if not defined AWX_RAG_NO_PAUSE pause
exit /b %DISPLAY_DBG_EXIT%
